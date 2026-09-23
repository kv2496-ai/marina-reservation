package edu.whoi.marina.domain;

import java.time.Instant;
import java.util.UUID;

/** Catch-all for import-time issues that aren't tied to one specific reservation record
 *  (an unparseable vessel block, a berth referenced only in the summary sheet, a tour whose
 *  vessel had no matching berth booking that day, etc). Never silently dropped. */
public class ReviewQueueItem {
    public String id = UUID.randomUUID().toString();
    public String category;
    public String sheet;
    public String location;
    public String description;
    public String rawData;
    public Instant createdAt = Instant.now();
    public boolean resolved = false;

    public ReviewQueueItem() {
    }

    public ReviewQueueItem(String category, String sheet, String location, String description, String rawData) {
        this.category = category;
        this.sheet = sheet;
        this.location = location;
        this.description = description;
        this.rawData = rawData;
    }
}
