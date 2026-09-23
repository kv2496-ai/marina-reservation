package edu.whoi.marina.domain;

public class RecurrenceRule {
    public RecurrenceFrequency frequency;
    public int interval = 1;
    /** Inclusive; generation stops at whichever of count/until is hit first. */
    public Integer count;
    public String until; // ISO local date, nullable
}
