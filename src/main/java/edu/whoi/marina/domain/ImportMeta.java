package edu.whoi.marina.domain;

import java.util.ArrayList;
import java.util.List;

/** Provenance for a reservation that was created by the historical spreadsheet importer,
 *  so staff can trace any record back to the exact cell it came from. */
public class ImportMeta {
    public String sheet;
    public String cellRef;
    public String originalText;
    /** 0.0-1.0, rough confidence the importer had in how it classified/dated this entry. */
    public double confidence = 1.0;
    public List<String> flags = new ArrayList<>();
}
