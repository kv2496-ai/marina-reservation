package edu.whoi.marina.importer;

import edu.whoi.marina.domain.DataQuality;
import edu.whoi.marina.domain.ReviewQueueItem;
import edu.whoi.marina.domain.Vessel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the ragged, header-optional "Science" / "Yachts" vessel sheets. Fields are not in fixed
 * columns and are sometimes spread across several rows per vessel, so each blank-row-delimited
 * block is scanned cell-by-cell and classified by pattern rather than by column position.
 */
public class VesselBlockParser {

    public static class Result {
        public final List<Vessel> vessels = new ArrayList<>();
        public final List<ReviewQueueItem> issues = new ArrayList<>();
    }

    private static final Pattern VESSEL_NAME = Pattern.compile(
            "^(M/V|R/V|S/V|F/V|M/Y|S/Y|OS/V|OSV|Tug|Barge)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOA_FIELD = Pattern.compile("LOA:\\s*(\\d+(?:\\.\\d+)?)'?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRAFT_FIELD = Pattern.compile("Draft:\\s*(\\d+(?:\\.\\d+)?)'?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_LOA_IN_NAME = Pattern.compile("(\\d+(?:\\.\\d+)?)'\\s*$");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern PHONE = Pattern.compile("(Cell:\\s*)?\\d{3}-\\d{4}|Cell:\\s*.+", Pattern.CASE_INSENSITIVE);

    private final DataFormatter formatter = new DataFormatter();

    public Result parse(Sheet sheet, boolean firstRowIsHeader) {
        Result result = new Result();
        int lastRow = sheet.getLastRowNum();

        List<List<String>> block = new ArrayList<>();
        int blockStartRow = firstRowIsHeader ? 1 : 0;

        for (int r = blockStartRow; r <= lastRow + 1; r++) {
            List<String> rowCells = (r <= lastRow) ? rowText(sheet, r) : List.of();
            boolean blank = rowCells.stream().allMatch(String::isBlank);

            if (blank) {
                if (!block.isEmpty()) {
                    processBlock(sheet.getSheetName(), block, result);
                    block = new ArrayList<>();
                }
            } else {
                block.add(rowCells);
            }
        }
        return result;
    }

    private void processBlock(String sheetName, List<List<String>> block, Result result) {
        String vesselName = null;
        Double loaFt = null;
        Double draftFt = null;
        String phone = null;
        String email = null;
        List<String> leftovers = new ArrayList<>();

        for (List<String> row : block) {
            for (String cell : row) {
                String v = cell.trim();
                if (v.isEmpty()) continue;

                if (vesselName == null && VESSEL_NAME.matcher(v).matches()) {
                    vesselName = v;
                    continue;
                }
                Matcher loaM = LOA_FIELD.matcher(v);
                if (loaM.find()) {
                    loaFt = Double.parseDouble(loaM.group(1));
                    continue;
                }
                Matcher draftM = DRAFT_FIELD.matcher(v);
                if (draftM.find()) {
                    draftFt = Double.parseDouble(draftM.group(1));
                    continue;
                }
                if (email == null && EMAIL.matcher(v).find()) {
                    email = EMAIL.matcher(v).results().findFirst().map(m -> m.group()).orElse(v);
                    continue;
                }
                if (URL.matcher(v).find()) {
                    leftovers.add(v); // kept in notes, not modeled as its own field
                    continue;
                }
                if (phone == null && PHONE.matcher(v).matches()) {
                    phone = v;
                    continue;
                }
                leftovers.add(v);
            }
        }

        if (vesselName == null) {
            result.issues.add(new ReviewQueueItem("UNPARSEABLE_VESSEL_BLOCK", sheetName, "n/a",
                    "Could not identify a vessel name in this block (no cell matched a known vessel prefix).",
                    String.join(" | ", block.stream().flatMap(List::stream).toList())));
            return;
        }

        if (loaFt == null) {
            Matcher m = TRAILING_LOA_IN_NAME.matcher(vesselName);
            if (m.find()) {
                loaFt = Double.parseDouble(m.group(1));
            }
        }

        Vessel vessel = new Vessel(vesselName);
        vessel.loaFt = loaFt;
        vessel.draftFt = draftFt;
        vessel.phone = phone;
        vessel.email = email;

        // Column order on the sheets is roughly (name, operator, contact, ...); leftovers are
        // consumed in that order and anything further is folded into notes rather than guessed at.
        List<String> missing = new ArrayList<>();
        int idx = 0;
        vessel.operator = idx < leftovers.size() ? leftovers.get(idx++) : null;
        vessel.contactName = idx < leftovers.size() ? leftovers.get(idx++) : null;
        if (idx < leftovers.size()) {
            vessel.notes = String.join("; ", leftovers.subList(idx, leftovers.size()));
        }

        if (vessel.operator == null) missing.add("operator");
        if (vessel.contactName == null) missing.add("contactName");
        if (phone == null) missing.add("phone");
        if (email == null) missing.add("email");
        if (loaFt == null) missing.add("loaFt");

        if (loaFt == null) {
            vessel.dataQuality = DataQuality.flagged(
                    "LOA could not be determined from the source sheet — berth-fit checks are skipped for this vessel until it's filled in.",
                    missing.toArray(new String[0]));
        } else if (!missing.isEmpty()) {
            vessel.dataQuality = DataQuality.flagged(
                    "Some fields could not be reliably parsed from the ragged source sheet layout.",
                    missing.toArray(new String[0]));
            vessel.dataQuality.flaggedForReview = phone == null && email == null; // no way to reach anyone
        }

        result.vessels.add(vessel);
    }

    private List<String> rowText(Sheet sheet, int r) {
        Row row = sheet.getRow(r);
        List<String> out = new ArrayList<>();
        if (row == null) return out;
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            out.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        return out;
    }
}
