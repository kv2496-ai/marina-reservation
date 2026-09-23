package edu.whoi.marina.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public class Reservation {

    public String id = UUID.randomUUID().toString();

    public ReservationKind kind = ReservationKind.VESSEL;

    /** Short display title, mainly for non-vessel kinds ("Community sail day", "Bollard replacement"). */
    public String title;

    /** Nullable — non-vessel events won't have one, and imported rows may not resolve to a known vessel. */
    public String vesselId;
    /** Denormalized snapshot of the vessel name at booking time, always populated when kind == VESSEL. */
    public String vesselNameSnapshot;

    /** Nullable only for imported rows the importer could not confidently map to a berth. */
    public String berthId;
    public String berthNameSnapshot;

    public LocalDate startDate;
    public LocalDate endDate;
    public LocalTime startTime;
    public LocalTime endTime;

    public ReservationStatus status = ReservationStatus.CONFIRMED;

    public boolean recurring = false;
    public RecurrenceRule recurrenceRule;
    public String recurrenceGroupId;

    /** Explicit staff-set flag for approved shared-berth arrangements (rafting). Overlaps between
     *  two rafting-approved reservations at the same berth are downgraded from hard conflict to warning. */
    public boolean raftingApproved = false;

    public ReservationSource source = ReservationSource.MANUAL;
    public ImportMeta importMeta;

    /** Free-text sub-category, mainly for kind == OTHER (e.g. FUELING, TRAINING, LOGISTICS). */
    public String category;

    public String notes;

    public Instant createdAt = Instant.now();
    public String createdBy;
    public Instant updatedAt = Instant.now();
    public String updatedBy;
}
