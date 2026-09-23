package edu.whoi.marina.importer;

import edu.whoi.marina.domain.Berth;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed set of berths found by inspecting all 23 yearly grid sheets plus the 8-year summary.
 * Row labels were verified to be identical (byte-for-byte, modulo whitespace) across every year
 * sheet, so exact-match lookup is safe here — see ISSUES.md for the one exception (Marsh Landing).
 */
public final class BerthCatalog {

    private BerthCatalog() {
    }

    /** Row label in the yearly grid sheets that is a section header, not a bookable berth. */
    public static final String GROUP_HEADER_LABEL = "North Finger Piers:";

    /** Grid row label -> (canonical name, length). Grid label equals the canonical name for the
     *  two berths that have no "- NNN'" suffix in the source data. */
    private static final Map<String, Map.Entry<String, Double>> GRID_LABEL_TO_NAME_AND_LENGTH = new LinkedHashMap<>();
    static {
        GRID_LABEL_TO_NAME_AND_LENGTH.put("North Pier West - 410'", new AbstractMap.SimpleEntry<>("North Pier West", 410.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("North Pier Face - 75'", new AbstractMap.SimpleEntry<>("North Pier Face", 75.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("North Pier East - 240'", new AbstractMap.SimpleEntry<>("North Pier East", 240.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("Inner Channel - 55'", new AbstractMap.SimpleEntry<>("Inner Channel", 55.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("South Float West - 90'", new AbstractMap.SimpleEntry<>("South Float West", 90.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("South Float East - 90'", new AbstractMap.SimpleEntry<>("South Float East", 90.0));
        GRID_LABEL_TO_NAME_AND_LENGTH.put("Small craft slips (institution boats)",
                new AbstractMap.SimpleEntry<>("Small craft slips (institution boats)", (Double) null));
    }

    public static List<Berth> canonicalBerths() {
        List<Berth> berths = new ArrayList<>();
        for (Map.Entry<String, Double> nameAndLength : GRID_LABEL_TO_NAME_AND_LENGTH.values()) {
            Berth b = new Berth(nameAndLength.getKey(), nameAndLength.getValue());
            if (nameAndLength.getValue() == null) {
                b.notes = "Length not recorded anywhere in the source spreadsheet — flagged for staff to fill in.";
            }
            berths.add(b);
        }

        Berth marsh = new Berth("Marsh Landing", null);
        marsh.unverified = true;
        marsh.notes = "Only appears as a row in the '8YR Dock Summary' sheet — never appears in any of the "
                + "23 yearly schedule grids. Imported as a placeholder berth; confirm whether this is a real, "
                + "still-active berth (possibly under a different name in the grids) before relying on it.";
        berths.add(marsh);

        return berths;
    }

    /** Builds the lookup used during import: exact (trimmed) grid row label -> Berth, based on the
     *  already-persisted canonical berths (matched by name). */
    public static Map<String, Berth> byGridLabel(List<Berth> berths) {
        Map<String, Berth> byName = new LinkedHashMap<>();
        for (Berth b : berths) {
            byName.put(b.name, b);
        }
        Map<String, Berth> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map.Entry<String, Double>> e : GRID_LABEL_TO_NAME_AND_LENGTH.entrySet()) {
            Berth b = byName.get(e.getValue().getKey());
            if (b != null) {
                result.put(e.getKey(), b);
            }
        }
        return result;
    }
}
