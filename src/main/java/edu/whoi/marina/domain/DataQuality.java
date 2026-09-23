package edu.whoi.marina.domain;

import java.util.ArrayList;
import java.util.List;

/** Tracks why a record (vessel, berth, reservation) may not be trustworthy enough for automatic
 *  validation, so the UI can surface it instead of silently assuming it's fine. */
public class DataQuality {
    public boolean flaggedForReview = false;
    public List<String> missingFields = new ArrayList<>();
    public String reviewReason;

    public static DataQuality clean() {
        return new DataQuality();
    }

    public static DataQuality flagged(String reason, String... missing) {
        DataQuality dq = new DataQuality();
        dq.flaggedForReview = true;
        dq.reviewReason = reason;
        for (String m : missing) {
            dq.missingFields.add(m);
        }
        return dq;
    }
}
