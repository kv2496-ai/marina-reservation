package edu.whoi.marina.importer;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.service.ValidationService;
import edu.whoi.marina.store.JsonCollectionStore;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * One-time (per empty database) import of the historical dock schedule workbook into the JSON
 * store. See ISSUES.md for the reasoning behind every non-obvious decision made here.
 */
@Service
public class SpreadsheetImportService {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportService.class);
    private static final Pattern YEAR_SHEET = Pattern.compile("^\\d{4}$");

    private final JsonCollectionStore<Berth> berthStore;
    private final JsonCollectionStore<Vessel> vesselStore;
    private final JsonCollectionStore<Reservation> reservationStore;
    private final JsonCollectionStore<ReviewQueueItem> reviewQueueStore;
    private final ValidationService validationService;

    public SpreadsheetImportService(JsonCollectionStore<Berth> berthStore,
                                     JsonCollectionStore<Vessel> vesselStore,
                                     JsonCollectionStore<Reservation> reservationStore,
                                     JsonCollectionStore<ReviewQueueItem> reviewQueueStore,
                                     ValidationService validationService) {
        this.berthStore = berthStore;
        this.vesselStore = vesselStore;
        this.reservationStore = reservationStore;
        this.reviewQueueStore = reviewQueueStore;
        this.validationService = validationService;
    }

    public ImportSummary importFrom(String xlsxPath) throws Exception {
        ImportSummary summary = new ImportSummary();
        List<ReviewQueueItem> reviewItems = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(new File(xlsxPath));
             Workbook wb = WorkbookFactory.create(fis)) {

            // 1) Berths
            List<Berth> berths = BerthCatalog.canonicalBerths();
            berthStore.saveAll(berths);
            summary.inc("berths.created", berths.size());
            Map<String, Berth> berthByGridLabel = BerthCatalog.byGridLabel(berths);
            Set<String> recognizedLabels = new HashSet<>(berthByGridLabel.keySet());

            // 2) Vessel registry from Science + Yachts sheets (richer data than the grid mentions)
            Map<String, Vessel> vesselsByMatchKey = new LinkedHashMap<>();
            for (String sheetName : List.of("Science", "Yachts")) {
                Sheet sheet = wb.getSheet(sheetName);
                if (sheet == null) continue;
                boolean hasHeader = "Science".equals(sheetName);
                VesselBlockParser.Result r = new VesselBlockParser().parse(sheet, hasHeader);
                reviewItems.addAll(r.issues);
                for (Vessel v : r.vessels) {
                    String key = VesselNameNormalizer.matchKey(v.name);
                    if (vesselsByMatchKey.containsKey(key)) {
                        reviewItems.add(new ReviewQueueItem("DUPLICATE_VESSEL", sheetName, v.name,
                                "Vessel \"" + v.name + "\" already registered from an earlier block in this "
                                        + "workbook — kept the first occurrence, this later one was not imported "
                                        + "as a second record. Compare and merge manually if they differ.",
                                describeVessel(v)));
                        summary.inc("vessels.duplicate_skipped");
                    } else {
                        vesselsByMatchKey.put(key, v);
                        summary.inc("vessels.from_registry");
                    }
                }
            }

            // 3) Grid sheets -> raw occupied cells
            List<RawGridEntry> allEntries = new ArrayList<>();
            for (Sheet sheet : wb) {
                if (!YEAR_SHEET.matcher(sheet.getSheetName()).matches()) continue;
                GridSheetParser parser = new GridSheetParser(recognizedLabels);
                allEntries.addAll(parser.parse(sheet));
                reviewItems.addAll(parser.issues());
            }
            summary.inc("grid.cells_read", allEntries.size());

            // 4) Classify + build reservations (with de-duplication across the year-boundary overlap
            //    the source data has: December of year N is repeated at the top of sheet N+1)
            List<Reservation> reservations = new ArrayList<>();
            Set<String> dedupeKeys = new HashSet<>();

            for (RawGridEntry entry : allEntries) {
                Berth berth = berthByGridLabel.get(entry.berthGridLabel);
                String text = entry.rawText;

                Reservation r = new Reservation();
                r.source = ReservationSource.IMPORTED;
                r.status = ReservationStatus.CONFIRMED;
                r.startDate = entry.startDate;
                r.endDate = entry.endDate;
                r.berthId = berth.id;
                r.berthNameSnapshot = berth.name;

                ImportMeta meta = new ImportMeta();
                meta.sheet = entry.sheetName;
                meta.cellRef = entry.cellRef();
                meta.originalText = text;
                r.importMeta = meta;

                String dedupeVesselPart;
                if (GridEntryClassifier.looksLikeVessel(text)) {
                    r.kind = ReservationKind.VESSEL;
                    String matchKey = VesselNameNormalizer.matchKey(text);
                    Vessel vessel = vesselsByMatchKey.computeIfAbsent(matchKey, k -> {
                        Vessel v = new Vessel(text);
                        v.dataQuality = DataQuality.flagged(
                                "Vessel only appears in the schedule grid, not in the Science/Yachts registry — "
                                        + "no LOA/contact info available from the source workbook.",
                                "loaFt", "operator", "contactName", "phone", "email");
                        summary.inc("vessels.from_grid_only");
                        return v;
                    });
                    r.vesselId = vessel.id;
                    r.vesselNameSnapshot = vessel.name;
                    dedupeVesselPart = matchKey;
                } else {
                    GridEntryClassifier.Classification c = GridEntryClassifier.classifyNonVessel(text);
                    r.kind = c.kind;
                    r.category = c.category;
                    r.title = text;
                    if (c.needsReview) {
                        meta.confidence = 0.4;
                        meta.flags.add(c.reviewReason);
                    }
                    dedupeVesselPart = "TXT:" + text.trim().toUpperCase();
                }

                if (!entry.durationConfident) {
                    meta.confidence = Math.min(meta.confidence, 0.6);
                    meta.flags.add("This sheet's format never recorded multi-day ranges — imported as a single "
                            + "day; the real stay may have been longer.");
                }

                String dedupeKey = String.join("|", berth.id, r.kind.toString(), dedupeVesselPart,
                        r.startDate.toString(), r.endDate.toString());
                if (!dedupeKeys.add(dedupeKey)) {
                    summary.inc("reservations.duplicate_skipped");
                    reviewItems.add(new ReviewQueueItem("DUPLICATE_RESERVATION", entry.sheetName, entry.cellRef(),
                            "Identical booking (same berth/vessel-or-text/date range) already imported from "
                                    + "another cell — likely the December year-boundary overlap between adjacent "
                                    + "year sheets. Not imported a second time.", text));
                    continue;
                }

                if (!meta.flags.isEmpty()) {
                    summary.inc("reservations.flagged");
                }
                reservations.add(r);
                summary.inc("reservations.imported");
            }

            // 5) Tours
            Sheet toursSheet = wb.getSheet("Tours");
            if (toursSheet != null) {
                ToursSheetParser.Result tr = new ToursSheetParser().parse(toursSheet);
                reviewItems.addAll(tr.issues);
                for (TourRow row : tr.rows) {
                    Reservation r = buildTourReservation(row, reservations, vesselsByMatchKey, reviewItems, summary);
                    reservations.add(r);
                    summary.inc("reservations.imported");
                    summary.inc("tours.imported");
                }
            }

            // 6) Persist
            vesselStore.saveAll(new ArrayList<>(vesselsByMatchKey.values()));
            reservationStore.saveAll(reservations);
            reviewQueueStore.saveAll(reviewItems);
            summary.inc("reviewQueue.items", reviewItems.size());

            for (Berth b : berths) {
                validationService.revalidateBerth(b.id);
            }
        }

        log.info(summary.toString());
        return summary;
    }

    private Reservation buildTourReservation(TourRow row, List<Reservation> reservationsSoFar,
                                              Map<String, Vessel> vesselsByMatchKey,
                                              List<ReviewQueueItem> reviewItems, ImportSummary summary) {
        Reservation r = new Reservation();
        r.kind = ReservationKind.TOUR;
        r.source = ReservationSource.IMPORTED;
        r.status = ReservationStatus.CONFIRMED;
        r.startDate = row.date;
        r.endDate = row.date;
        r.startTime = row.time;

        StringBuilder notes = new StringBuilder();
        if (row.guide != null) notes.append("Guide: ").append(row.guide).append(". ");
        if (row.guest != null) notes.append("Guest: ").append(row.guest).append(". ");
        if (row.peopleText != null) notes.append("Party size: ").append(row.peopleText).append(". ");
        if (row.notes != null) notes.append(row.notes);
        r.notes = notes.toString().trim();

        ImportMeta meta = new ImportMeta();
        meta.sheet = "Tours";
        meta.cellRef = "row" + (row.rowIndex + 1);
        meta.originalText = row.dockShipName;
        r.importMeta = meta;

        if (row.dockShipName != null) {
            r.vesselNameSnapshot = row.dockShipName;
            String matchKey = VesselNameNormalizer.matchKey(row.dockShipName);
            Vessel vessel = vesselsByMatchKey.get(matchKey);
            if (vessel != null) {
                r.vesselId = vessel.id;
            }

            List<Reservation> matches = reservationsSoFar.stream()
                    .filter(res -> res.kind == ReservationKind.VESSEL)
                    .filter(res -> matchKey.equals(VesselNameNormalizer.matchKey(res.vesselNameSnapshot)))
                    .filter(res -> res.startDate != null && res.endDate != null
                            && !res.startDate.isAfter(row.date) && !res.endDate.isBefore(row.date))
                    .toList();

            if (matches.size() == 1) {
                r.berthId = matches.get(0).berthId;
                r.berthNameSnapshot = matches.get(0).berthNameSnapshot;
            } else if (matches.isEmpty()) {
                meta.confidence = 0.3;
                meta.flags.add("No berth reservation found for \"" + row.dockShipName + "\" covering " + row.date
                        + " — could not infer which berth this tour departed from.");
                reviewItems.add(new ReviewQueueItem("TOUR_NO_BERTH_MATCH", "Tours", "row " + (row.rowIndex + 1),
                        "No matching vessel booking found for tour aboard \"" + row.dockShipName + "\" on " + row.date + ".",
                        row.dockShipName));
                summary.inc("tours.unresolved_berth");
            } else {
                r.berthId = matches.get(0).berthId;
                r.berthNameSnapshot = matches.get(0).berthNameSnapshot;
                meta.confidence = 0.7;
                meta.flags.add("Vessel had " + matches.size() + " overlapping berth bookings on " + row.date
                        + " — picked the first one; verify manually.");
            }
        } else {
            meta.confidence = 0.3;
            meta.flags.add("No vessel/ship named for this tour.");
        }

        r.title = "Tour" + (row.dockShipName != null ? " aboard " + row.dockShipName : "");
        return r;
    }

    private String describeVessel(Vessel v) {
        return String.format("operator=%s, contact=%s, phone=%s, email=%s, loaFt=%s",
                v.operator, v.contactName, v.phone, v.email, v.loaFt);
    }
}
