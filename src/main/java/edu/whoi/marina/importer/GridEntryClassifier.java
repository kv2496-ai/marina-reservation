package edu.whoi.marina.importer;

import edu.whoi.marina.domain.ReservationKind;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Classifies the free-text found in a single grid cell into a reservation kind/category.
 * Built directly from the distinct non-vessel strings actually observed across all 23 yearly
 * sheets (575 distinct entries total, 46 non-vessel-prefixed) — see ISSUES.md for the full list.
 */
public final class GridEntryClassifier {

    private GridEntryClassifier() {
    }

    private static final Pattern VESSEL_PREFIX =
            Pattern.compile("^(M/V|R/V|S/V|F/V|M/Y|S/Y|OS/V|OSV|Tug|Barge)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean looksLikeVessel(String text) {
        return VESSEL_PREFIX.matcher(text.trim()).find();
    }

    private static final List<String> COMMUNITY_EVENT_KEYWORDS = List.of(
            "community sail day", "campus event", "student tour", "public open house",
            "donor reception", "film crew on dock", "science stroll", "road race");

    private static final List<String> MAINTENANCE_KEYWORDS = List.of(
            "bollard replacement", "float rebuild", "dock maintenance", "pier repair",
            "ultrasonic pier test", "concrete work", "utility work on pier", "paving near dock",
            "dock inspection", "crane access");

    private static final List<String> OTHER_LOGISTICS_KEYWORDS = List.of(
            "bunker barge", "fuel truck", "fueling", "bunkering", "water/slops pumping",
            "provisioning", "load equipment", "safety training", "rescue drill", "dive training",
            "touch and go", "emergency port call");

    /** Fragments that read as scheduling annotations (an ETA, a departure time note) rather than
     *  a bookable event in their own right. Never silently discarded — always routed to review. */
    private static final Pattern BARE_ANNOTATION = Pattern.compile(
            "^(eta|etd|departs?|arrival|arrives?|departure)\\b|^\\d{3,4}$|delayed due to weather|"
                    + "returns from sea trials|wire spooling", Pattern.CASE_INSENSITIVE);

    public static final class Classification {
        public final ReservationKind kind;
        public final String category;
        public final boolean needsReview;
        public final String reviewReason;

        Classification(ReservationKind kind, String category, boolean needsReview, String reviewReason) {
            this.kind = kind;
            this.category = category;
            this.needsReview = needsReview;
            this.reviewReason = reviewReason;
        }
    }

    public static Classification classifyNonVessel(String rawText) {
        String t = rawText.trim().toLowerCase();

        if (BARE_ANNOTATION.matcher(t).find()) {
            return new Classification(ReservationKind.OTHER, "UNCLASSIFIED_ANNOTATION", true,
                    "Text looks like a scheduling annotation (arrival/departure time note) rather than "
                            + "a standalone bookable event: \"" + rawText + "\"");
        }
        for (String kw : COMMUNITY_EVENT_KEYWORDS) {
            if (t.contains(kw)) {
                return new Classification(ReservationKind.COMMUNITY_EVENT, null, false, null);
            }
        }
        for (String kw : MAINTENANCE_KEYWORDS) {
            if (t.contains(kw)) {
                return new Classification(ReservationKind.MAINTENANCE, null, false, null);
            }
        }
        for (String kw : OTHER_LOGISTICS_KEYWORDS) {
            if (t.contains(kw)) {
                return new Classification(ReservationKind.OTHER, "LOGISTICS", false, null);
            }
        }
        if (t.equals("holiday")) {
            return new Classification(ReservationKind.OTHER, "HOLIDAY", true,
                    "\"Holiday\" is ambiguous — unclear whether this means the berth is closed or is just "
                            + "an informational note. Imported as OTHER; confirm intended meaning.");
        }

        return new Classification(ReservationKind.OTHER, "UNCLASSIFIED", true,
                "No keyword match for \"" + rawText + "\" — imported as OTHER, needs manual categorization.");
    }
}
