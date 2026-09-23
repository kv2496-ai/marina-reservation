package edu.whoi.marina.importer;

import edu.whoi.marina.domain.ReviewQueueItem;
import org.apache.poi.ss.usermodel.*;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Parses the "Tours" sheet: a simple header + rows once the two informational banner rows and
 *  the header itself are skipped. Dates are stored as raw Excel serial numbers (no date format
 *  applied to the cell), and "Time" is a free-text HHmm number or the literal "tbd". */
public class ToursSheetParser {

    public static class Result {
        public final List<TourRow> rows = new ArrayList<>();
        public final List<ReviewQueueItem> issues = new ArrayList<>();
    }

    private final DataFormatter formatter = new DataFormatter();

    public Result parse(Sheet sheet) {
        Result result = new Result();
        int headerRow = findHeaderRow(sheet);
        if (headerRow < 0) {
            result.issues.add(new ReviewQueueItem("IMPORT_FORMAT", sheet.getSheetName(), "n/a",
                    "Could not find the 'Date' header row in the Tours sheet — sheet skipped entirely.", null));
            return result;
        }

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String dateText = text(row, 0);
            String timeText = text(row, 1);
            String guide = text(row, 2);
            String guest = text(row, 3);
            String people = text(row, 4);
            String dockShip = text(row, 5);
            String notes = text(row, 6);

            if (dateText.isBlank() && timeText.isBlank() && guide.isBlank() && guest.isBlank() && dockShip.isBlank()) {
                continue; // fully blank row
            }
            if (dateText.isBlank()) {
                // A row with only stray text and no date (e.g. an orphaned note continuation) —
                // not enough information to build a tour record; flag rather than drop silently.
                result.issues.add(new ReviewQueueItem("INCOMPLETE_TOUR_ROW", sheet.getSheetName(), "row " + (r + 1),
                        "Tours row has no date and can't be imported as a standalone record.",
                        String.join(" | ", dateText, timeText, guide, guest, people, dockShip, notes)));
                continue;
            }

            TourRow tr = new TourRow();
            tr.rowIndex = r;
            tr.guide = blankToNull(guide);
            tr.guest = blankToNull(guest);
            tr.peopleText = blankToNull(people);
            tr.dockShipName = blankToNull(dockShip);
            tr.notes = blankToNull(notes);

            java.time.LocalDate parsedDate = parseExcelSerialDate(row.getCell(0), dateText);
            if (parsedDate == null) {
                result.issues.add(new ReviewQueueItem("UNPARSEABLE_TOUR_DATE", sheet.getSheetName(), "row " + (r + 1),
                        "Could not parse tour date from \"" + dateText + "\".", dateText));
                continue;
            }
            tr.date = parsedDate;
            tr.time = parseTime(timeText);

            result.rows.add(tr);
        }
        return result;
    }

    private LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("tbd")) {
            return null;
        }
        String digits = raw.trim();
        try {
            int value = (int) Double.parseDouble(digits);
            int hh = value / 100;
            int mm = value % 100;
            if (hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59) {
                return LocalTime.of(hh, mm);
            }
        } catch (NumberFormatException ignored) {
        }
        return null; // unparseable time left null rather than guessed
    }

    private java.time.LocalDate parseExcelSerialDate(Cell cell, String fallbackText) {
        try {
            if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
            }
            double serial = Double.parseDouble(fallbackText.trim());
            return DateUtil.getLocalDateTime(serial).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            if ("Date".equalsIgnoreCase(text(sheet.getRow(r), 0))) {
                return r;
            }
        }
        return -1;
    }

    private String text(Row row, int c) {
        if (row == null) return "";
        Cell cell = row.getCell(c);
        if (cell == null) return "";
        return formatter.formatCellValue(cell).trim();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
