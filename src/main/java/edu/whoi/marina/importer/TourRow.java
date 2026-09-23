package edu.whoi.marina.importer;

import java.time.LocalDate;
import java.time.LocalTime;

public class TourRow {
    public LocalDate date;
    public LocalTime time; // nullable ("tbd" or unparseable)
    public String guide;
    public String guest;
    public String peopleText; // kept as text since some entries are "~17" (estimate)
    public String dockShipName;
    public String notes;
    public int rowIndex;
}
