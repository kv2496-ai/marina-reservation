package edu.whoi.marina.importer;

import java.util.regex.Pattern;

/** Vessel names in the grid sheets are usually bare ("S/V IRON PETREL") while the Science/Yachts
 *  sheets often embed the LOA in the same cell ("S/V Iron Petrel 32'"). Matching across sources
 *  needs a key that ignores both case/whitespace AND a trailing embedded length. */
public final class VesselNameNormalizer {

    private VesselNameNormalizer() {
    }

    private static final Pattern TRAILING_LOA = Pattern.compile("\\s+\\d+(\\.\\d+)?'\\s*$");

    public static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    public static String matchKey(String raw) {
        if (raw == null) return "";
        String stripped = TRAILING_LOA.matcher(raw.trim()).replaceAll("");
        return normalize(stripped);
    }
}
