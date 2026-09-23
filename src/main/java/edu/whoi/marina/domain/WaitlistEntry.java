package edu.whoi.marina.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vessel waiting for any berth that fits its LOA for a fixed date range, after being bumped
 * from a conflict or an oversized-for-its-berth warning with no immediately available alternative.
 */
public class WaitlistEntry {
    public String id = UUID.randomUUID().toString();

    public String vesselId;
    public String vesselNameSnapshot;
    public Double loaFt;

    public LocalDate startDate;
    public LocalDate endDate;

    /** The berth/reservation this waitlist entry displaced the vessel from, for traceability. */
    public String originBerthId;
    public String originBerthNameSnapshot;
    public String originReservationId;

    public WaitlistStatus status = WaitlistStatus.WAITING;

    /** Populated once a fitting berth is found, cleared if the match is declined. */
    public String matchedBerthId;
    public String matchedBerthNameSnapshot;

    /** Populated once confirmed into a real reservation. */
    public String fulfilledReservationId;

    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public String notes;
}
