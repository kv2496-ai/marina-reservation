package edu.whoi.marina.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A physical berth. {@code restrictions} is an open map (not a fixed set of columns) so future
 * requirements (draft limits, electrical, water, weather closures, ...) can be added without a
 * schema migration.
 */
public class Berth {

    public String id = UUID.randomUUID().toString();

    @NotBlank
    public String name;

    /** Nullable: some historical berths (e.g. "Small craft slips") never had a recorded length. */
    public Double lengthFt;

    public BerthStatus status = BerthStatus.ACTIVE;

    public String notes;

    /** Open-ended future restrictions: minDraftFt, electrical, water, weatherClosure, etc. */
    public Map<String, Object> restrictions = new LinkedHashMap<>();

    /** True for berths that only exist because they were referenced elsewhere (e.g. the 8-year
     *  summary sheet) but never appear in the actual schedule grids. Surfaced to staff, not hidden. */
    public boolean unverified = false;

    public Berth() {
    }

    public Berth(String name, Double lengthFt) {
        this.name = name;
        this.lengthFt = lengthFt;
    }
}
