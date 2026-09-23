package edu.whoi.marina.importer;

import java.time.LocalDate;

/** One occupied cell (or merged run of cells) read from a yearly schedule grid, before it has
 *  been classified as a vessel/event or turned into a Reservation. */
public class RawGridEntry {
    public final String sheetName;
    public final String berthGridLabel;
    public final LocalDate startDate;
    public final LocalDate endDate;
    public final String rawText;
    public final int rowIndex;
    public final int colIndex;
    /** False when the sheet's format never recorded merged ranges at all, so a multi-day stay
     *  could be hiding behind what we can only confirm is a single occupied day. */
    public final boolean durationConfident;

    public RawGridEntry(String sheetName, String berthGridLabel, LocalDate startDate, LocalDate endDate,
                         String rawText, int rowIndex, int colIndex, boolean durationConfident) {
        this.sheetName = sheetName;
        this.berthGridLabel = berthGridLabel;
        this.startDate = startDate;
        this.endDate = endDate;
        this.rawText = rawText;
        this.rowIndex = rowIndex;
        this.colIndex = colIndex;
        this.durationConfident = durationConfident;
    }

    public String cellRef() {
        return "R" + (rowIndex + 1) + "C" + (colIndex + 1);
    }
}
