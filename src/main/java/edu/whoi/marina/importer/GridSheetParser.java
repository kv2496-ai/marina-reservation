package edu.whoi.marina.importer;

import edu.whoi.marina.domain.ReviewQueueItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one yearly "MONTH YEAR" grid sheet into raw occupied-cell entries.
 *
 * The sheet's layout is detected dynamically rather than assumed, because it changes across the
 * 23 years in the source workbook (day-1's column shifts, and only 2010+ sheets use merged cells
 * to record a multi-day stay's true end date). See ISSUES.md for the format survey this is based on.
 */
public class GridSheetParser {

    private static final Pattern MONTH_TITLE = Pattern.compile(
            "^(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    /** 2014-2019 use a bare month name with no year in the same cell (e.g. "January") — the year
     *  has to come from the sheet itself. Verified those six sheets contain exactly one Jan-Dec
     *  run each with no cross-year spillover, so "sheet name == year" is safe for this format. */
    private static final Pattern BARE_MONTH_TITLE = Pattern.compile(
            "^(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOW_TOKEN = Pattern.compile("^(S|M|T|W|TR|F)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTH_NUMBERS = Map.ofEntries(
            Map.entry("JANUARY", 1), Map.entry("FEBRUARY", 2), Map.entry("MARCH", 3), Map.entry("APRIL", 4),
            Map.entry("MAY", 5), Map.entry("JUNE", 6), Map.entry("JULY", 7), Map.entry("AUGUST", 8),
            Map.entry("SEPTEMBER", 9), Map.entry("OCTOBER", 10), Map.entry("NOVEMBER", 11), Map.entry("DECEMBER", 12));

    private final DataFormatter formatter = new DataFormatter();
    private final Set<String> recognizedLabels;
    private final List<ReviewQueueItem> issues = new ArrayList<>();

    public GridSheetParser(Set<String> recognizedBerthLabels) {
        this.recognizedLabels = recognizedBerthLabels;
    }

    public List<ReviewQueueItem> issues() {
        return issues;
    }

    public List<RawGridEntry> parse(Sheet sheet) {
        boolean sheetHasAnyMerges = sheet.getNumMergedRegions() > 0;
        Map<Integer, List<CellRangeAddress>> mergesByRow = mergesByRow(sheet);

        Integer sheetYearFallback = null;
        try {
            sheetYearFallback = Integer.parseInt(sheet.getSheetName().trim());
        } catch (NumberFormatException ignored) {
        }

        int lastRow = sheet.getLastRowNum();
        List<Integer> titleRows = new ArrayList<>();
        List<int[]> titleMonthYear = new ArrayList<>(); // [month, year] parallel to titleRows
        for (int r = 0; r <= lastRow; r++) {
            String col0 = text(sheet, r, 0).trim();
            Matcher m = MONTH_TITLE.matcher(col0);
            if (m.find()) {
                titleRows.add(r);
                titleMonthYear.add(new int[]{MONTH_NUMBERS.get(m.group(1).toUpperCase()), Integer.parseInt(m.group(2))});
                continue;
            }
            Matcher bare = BARE_MONTH_TITLE.matcher(col0);
            if (bare.matches() && sheetYearFallback != null) {
                titleRows.add(r);
                titleMonthYear.add(new int[]{MONTH_NUMBERS.get(bare.group(1).toUpperCase()), sheetYearFallback});
            }
        }

        if (titleRows.isEmpty()) {
            // Never silently skip an entire sheet: if neither title format matched anywhere, that's
            // an unrecognized layout, not "no data" — flag it loudly rather than importing nothing.
            issues.add(new ReviewQueueItem("IMPORT_FORMAT", sheet.getSheetName(), "whole sheet",
                    "No month/year header of any known format was found anywhere in this sheet — "
                            + "none of its data was imported. This sheet's layout needs to be checked by hand.",
                    null));
        }

        List<RawGridEntry> results = new ArrayList<>();
        for (int i = 0; i < titleRows.size(); i++) {
            int blockStart = titleRows.get(i);
            int blockEnd = (i + 1 < titleRows.size()) ? titleRows.get(i + 1) : lastRow + 1;
            int month = titleMonthYear.get(i)[0];
            int year = titleMonthYear.get(i)[1];

            int startCol = findDowStartColumn(sheet, blockStart, Math.min(blockStart + 3, blockEnd - 1));
            if (startCol < 0) {
                issues.add(new ReviewQueueItem("IMPORT_FORMAT", sheet.getSheetName(), "row " + (blockStart + 1),
                        "No day-of-week header row found for month block starting at row " + (blockStart + 1)
                                + " (\"" + text(sheet, blockStart, 0) + "\") — block skipped entirely.",
                        text(sheet, blockStart, 0)));
                continue;
            }

            int daysInMonth = YearMonth.of(year, month).lengthOfMonth();

            for (int r = blockStart; r < blockEnd; r++) {
                String label = text(sheet, r, 0).trim();
                if (label.isEmpty() || !recognizedLabels.contains(label)) {
                    if (!label.isEmpty() && !label.equals(BerthCatalog.GROUP_HEADER_LABEL)
                            && !MONTH_TITLE.matcher(label).find() && !BARE_MONTH_TITLE.matcher(label).matches()) {
                        // Non-blank col0 that isn't a known berth, the group header, or another title —
                        // flag once so staff can check nothing was missed, but don't guess what it means.
                        issues.add(new ReviewQueueItem("IMPORT_FORMAT", sheet.getSheetName(), "row " + (r + 1),
                                "Row " + (r + 1) + " has unrecognized label \"" + label + "\" — row skipped.", label));
                    }
                    continue;
                }

                Row row = sheet.getRow(r);
                if (row == null) continue;
                int lastCellCol = row.getLastCellNum();
                for (int c = startCol; c < lastCellCol; c++) {
                    int day = c - startCol + 1;
                    if (day > daysInMonth) break;
                    String text = text(sheet, r, c).trim();
                    if (text.isEmpty()) continue;

                    CellRangeAddress merge = findAnchorMerge(mergesByRow, r, c);
                    int endCol = merge != null ? merge.getLastColumn() : c;
                    int endDay = Math.min(endCol - startCol + 1, daysInMonth);

                    LocalDate start = LocalDate.of(year, month, day);
                    LocalDate end = LocalDate.of(year, month, endDay);
                    boolean confident = sheetHasAnyMerges; // if the sheet never merges, we can't trust any single-day reading as complete

                    results.add(new RawGridEntry(sheet.getSheetName(), label, start, end, text, r, c, confident));
                }
            }
        }
        return results;
    }

    private int findDowStartColumn(Sheet sheet, int fromRow, int toRow) {
        for (int r = fromRow; r <= toRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int runStart = -1, runLen = 0, bestStart = -1, bestLen = 0;
            int lastCol = row.getLastCellNum();
            for (int c = 1; c < Math.max(lastCol, 1); c++) {
                String v = text(sheet, r, c).trim();
                if (DOW_TOKEN.matcher(v).matches()) {
                    if (runStart < 0) runStart = c;
                    runLen++;
                } else {
                    if (runLen > bestLen) {
                        bestLen = runLen;
                        bestStart = runStart;
                    }
                    runStart = -1;
                    runLen = 0;
                }
            }
            if (runLen > bestLen) {
                bestLen = runLen;
                bestStart = runStart;
            }
            if (bestLen >= 5) {
                return bestStart;
            }
        }
        return -1;
    }

    private CellRangeAddress findAnchorMerge(Map<Integer, List<CellRangeAddress>> mergesByRow, int row, int col) {
        List<CellRangeAddress> ranges = mergesByRow.get(row);
        if (ranges == null) return null;
        for (CellRangeAddress range : ranges) {
            if (range.getFirstColumn() == col && range.getFirstRow() == row && range.getLastRow() == row) {
                return range;
            }
        }
        return null;
    }

    private Map<Integer, List<CellRangeAddress>> mergesByRow(Sheet sheet) {
        Map<Integer, List<CellRangeAddress>> map = new HashMap<>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress range = sheet.getMergedRegion(i);
            map.computeIfAbsent(range.getFirstRow(), k -> new ArrayList<>()).add(range);
        }
        return map;
    }

    private String text(Sheet sheet, int r, int c) {
        Row row = sheet.getRow(r);
        if (row == null) return "";
        Cell cell = row.getCell(c);
        if (cell == null) return "";
        try {
            return formatter.formatCellValue(cell);
        } catch (Exception e) {
            return "";
        }
    }
}
