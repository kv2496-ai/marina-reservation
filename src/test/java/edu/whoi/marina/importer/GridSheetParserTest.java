package edu.whoi.marina.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GridSheetParserTest {

    private static final Set<String> LABELS = Set.of("North Pier West - 410'", "North Pier East - 240'");

    private void setRow(Sheet sheet, int r, String... values) {
        Row row = sheet.createRow(r);
        for (int c = 0; c < values.length; c++) {
            if (values[c] != null) row.createCell(c).setCellValue(values[c]);
        }
    }

    @Test
    void parsesMonthYearFormat_singleDayEntries() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("1997");
            setRow(sheet, 0, "AUGUST 1997");
            setRow(sheet, 1, "", "F", "S", "S", "M", "T", "W");
            setRow(sheet, 2, "", "1");
            setRow(sheet, 3, "North Pier West - 410'", "", "F/V Swift Dory");

            List<RawGridEntry> entries = new GridSheetParser(LABELS).parse(sheet);

            assertThat(entries).hasSize(1);
            RawGridEntry e = entries.get(0);
            assertThat(e.rawText).isEqualTo("F/V Swift Dory");
            assertThat(e.startDate).isEqualTo(LocalDate.of(1997, 8, 2));
            assertThat(e.endDate).isEqualTo(LocalDate.of(1997, 8, 2));
            assertThat(e.durationConfident).isFalse(); // sheet has no merges at all
        }
    }

    @Test
    void parsesBareMonthNameFormat_usingSheetNameAsYear() throws Exception {
        // Regression test: 2014-2019 in the real workbook use a bare "January" title with no year,
        // which an earlier version of this parser never matched at all, silently importing zero
        // reservations for six entire years without raising any flag.
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2018");
            setRow(sheet, 0, "January", "", "1", "2", "3", "4", "5", "6");
            setRow(sheet, 1, "", "", "M", "T", "W", "TR", "F", "S");
            setRow(sheet, 2, "North Pier West - 410'", "", "R/V GOLDEN COMPASS");

            GridSheetParser parser = new GridSheetParser(LABELS);
            List<RawGridEntry> entries = parser.parse(sheet);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).startDate).isEqualTo(LocalDate.of(2018, 1, 1));
            assertThat(parser.issues()).isEmpty();
        }
    }

    @Test
    void wholeSheetWithNoRecognizedTitleFormat_isFlaggedNotSilentlySkipped() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2099");
            setRow(sheet, 0, "Some Unrecognized Header");
            setRow(sheet, 1, "North Pier West - 410'", "", "R/V Mystery");

            GridSheetParser parser = new GridSheetParser(LABELS);
            List<RawGridEntry> entries = parser.parse(sheet);

            assertThat(entries).isEmpty();
            assertThat(parser.issues()).hasSize(1);
            assertThat(parser.issues().get(0).description).contains("No month/year header of any known format");
        }
    }

    @Test
    void mergedCell_recordsFullSpanAsConfidentDuration() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2010");
            setRow(sheet, 0, "JANUARY 2010", "", "", "", "1", "2", "3", "4", "5");
            setRow(sheet, 1, "", "", "", "", "F", "S", "S", "M", "T");
            setRow(sheet, 2, "North Pier West - 410'", "", "", "", "M/V NORTHERN HARBOR");
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 4, 6)); // days 1-3

            List<RawGridEntry> entries = new GridSheetParser(LABELS).parse(sheet);

            assertThat(entries).hasSize(1);
            RawGridEntry e = entries.get(0);
            assertThat(e.startDate).isEqualTo(LocalDate.of(2010, 1, 1));
            assertThat(e.endDate).isEqualTo(LocalDate.of(2010, 1, 3));
            assertThat(e.durationConfident).isTrue();
        }
    }

    @Test
    void groupHeaderRow_isSkippedWithoutBeingFlagged() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2016");
            setRow(sheet, 0, "MARCH 2016");
            setRow(sheet, 1, "", "S", "M", "T", "W", "TR", "F");
            setRow(sheet, 2, BerthCatalog.GROUP_HEADER_LABEL, "", "should not appear as an entry");

            GridSheetParser parser = new GridSheetParser(LABELS);
            List<RawGridEntry> entries = parser.parse(sheet);

            assertThat(entries).isEmpty();
            assertThat(parser.issues()).isEmpty();
        }
    }

    @Test
    void unrecognizedBerthLabel_isFlaggedForReview() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("2016");
            setRow(sheet, 0, "MARCH 2016");
            setRow(sheet, 1, "", "S", "M", "T", "W", "TR", "F");
            setRow(sheet, 2, "Some New Berth Nobody Catalogued", "", "M/V Ghost");

            GridSheetParser parser = new GridSheetParser(LABELS);
            List<RawGridEntry> entries = parser.parse(sheet);

            assertThat(entries).isEmpty();
            assertThat(parser.issues()).anyMatch(i -> i.description.contains("Some New Berth Nobody Catalogued"));
        }
    }
}
