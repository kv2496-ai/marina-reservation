package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Re-runs the scheduling rules for a berth whenever any reservation touching it changes.
 *
 * Rules (see ARCHITECTURE.md for rationale):
 *  - Two active (non-canceled) reservations at the same berth with overlapping date ranges are a
 *    HARD_CONFLICT only when both are CONFIRMED and it is not an explicitly rafting-approved pair;
 *    any other overlap (draft/pending involved, or rafting-approved) is a WARNING for staff judgment.
 *  - A vessel's LOA exceeding the berth's recorded length is a WARNING, not a hard block.
 *  - Missing/flagged vessel or berth data produces a DATA_QUALITY warning instead of being assumed fine.
 *  - Booking a CLOSED or RESTRICTED berth produces a WARNING.
 */
@Service
public class ValidationService {

    private final JsonCollectionStore<Reservation> reservationStore;
    private final JsonCollectionStore<ValidationFlag> flagStore;
    private final JsonCollectionStore<Vessel> vesselStore;
    private final JsonCollectionStore<Berth> berthStore;

    public ValidationService(JsonCollectionStore<Reservation> reservationStore,
                              JsonCollectionStore<ValidationFlag> flagStore,
                              JsonCollectionStore<Vessel> vesselStore,
                              JsonCollectionStore<Berth> berthStore) {
        this.reservationStore = reservationStore;
        this.flagStore = flagStore;
        this.vesselStore = vesselStore;
        this.berthStore = berthStore;
    }

    /** Removes every flag attached to a reservation that no longer exists. revalidateBerth only
     *  ever recomputes flags for reservations CURRENTLY at a berth, so a deleted reservation's own
     *  flags would otherwise never be cleared — call this explicitly whenever one is deleted. */
    public void purgeFlagsForReservation(String reservationId) {
        flagStore.transact(flags -> flags.removeIf(f -> reservationId.equals(f.reservationId)));
    }

    /** Recomputes every validation flag for every reservation at the given berth. Called after
     *  any create/update/cancel/delete that touches that berth (including the reservation's old
     *  berth, if the berth was changed on edit). */
    public void revalidateBerth(String berthId) {
        if (berthId == null) {
            return;
        }
        List<Reservation> atBerth = reservationStore.findAll().stream()
                .filter(r -> berthId.equals(r.berthId))
                .filter(r -> r.status != ReservationStatus.CANCELED)
                .toList();

        Optional<Berth> berth = berthStore.findById(berthId);

        flagStore.transact(flags -> {
            flags.removeIf(f -> atBerth.stream().anyMatch(r -> r.id.equals(f.reservationId)));
            for (Reservation r : atBerth) {
                flags.addAll(computeFlagsFor(r, atBerth, berth.orElse(null)));
            }
            return null;
        });
    }

    private List<ValidationFlag> computeFlagsFor(Reservation r, List<Reservation> peersAtBerth, Berth berth) {
        List<ValidationFlag> flags = new ArrayList<>();

        // --- overlap / double-booking ---
        for (Reservation other : peersAtBerth) {
            if (other.id.equals(r.id)) continue;
            if (!datesOverlap(r, other)) continue;
            // report each unordered pair once (lower id first) to avoid duplicate flags
            if (r.id.compareTo(other.id) > 0) continue;

            boolean bothConfirmed = r.status == ReservationStatus.CONFIRMED && other.status == ReservationStatus.CONFIRMED;
            boolean raftingPair = r.raftingApproved && other.raftingApproved;

            ValidationSeverity severity = (bothConfirmed && !raftingPair) ? ValidationSeverity.HARD_CONFLICT : ValidationSeverity.WARNING;
            String msg = String.format("Overlaps with %s (%s, %s to %s)%s",
                    describe(other), other.status, other.startDate, other.endDate,
                    raftingPair ? " — both marked rafting-approved" : "");

            ValidationFlag flagForR = new ValidationFlag(r.id, severity, ValidationType.DOUBLE_BOOKING, msg);
            flagForR.relatedReservationId = other.id;
            flags.add(flagForR);

            ValidationFlag flagForOther = new ValidationFlag(other.id, severity,
                    ValidationType.DOUBLE_BOOKING,
                    String.format("Overlaps with %s (%s, %s to %s)%s", describe(r), r.status, r.startDate, r.endDate,
                            raftingPair ? " — both marked rafting-approved" : ""));
            flagForOther.relatedReservationId = r.id;
            flags.add(flagForOther);
        }

        // --- berth data quality / LOA fit ---
        if (berth == null) {
            flags.add(new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_BERTH_INFO,
                    "Reservation references a berth that no longer exists in the berth database."));
        } else {
            if (berth.status != BerthStatus.ACTIVE) {
                flags.add(new ValidationFlag(r.id, ValidationSeverity.WARNING, ValidationType.BERTH_CLOSED,
                        "Berth \"" + berth.name + "\" is currently " + berth.status + "."));
            }
            if (berth.lengthFt == null) {
                flags.add(new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_BERTH_INFO,
                        "Berth \"" + berth.name + "\" has no recorded length — cannot validate vessel fit."));
            }
            if (r.kind == ReservationKind.VESSEL && r.vesselId != null && berth.lengthFt != null) {
                vesselStore.findById(r.vesselId).ifPresent(v -> {
                    if (v.loaFt != null && v.loaFt > berth.lengthFt) {
                        flags.add(new ValidationFlag(r.id, ValidationSeverity.WARNING, ValidationType.LOA_EXCEEDS_BERTH,
                                String.format("Vessel LOA %.0f' exceeds berth \"%s\" length %.0f'.", v.loaFt, berth.name, berth.lengthFt)));
                    }
                });
            }
        }

        // --- vessel data quality ---
        if (r.kind == ReservationKind.VESSEL) {
            if (r.vesselId == null) {
                flags.add(new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_VESSEL_INFO,
                        "No vessel record linked to this reservation."));
            } else {
                vesselStore.findById(r.vesselId).ifPresent(v -> {
                    if (v.dataQuality != null && v.dataQuality.flaggedForReview) {
                        flags.add(new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_VESSEL_INFO,
                                "Vessel \"" + v.name + "\" is flagged for review: " + v.dataQuality.reviewReason));
                    }
                    if (v.loaFt == null) {
                        flags.add(new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_VESSEL_INFO,
                                "Vessel \"" + v.name + "\" has no recorded LOA — cannot validate berth fit."));
                    }
                });
            }
        }

        return flags;
    }

    private String describe(Reservation r) {
        if (r.kind == ReservationKind.VESSEL && r.vesselNameSnapshot != null) {
            return r.vesselNameSnapshot;
        }
        return r.title != null ? r.title : r.kind.toString();
    }

    public static boolean datesOverlap(Reservation a, Reservation b) {
        if (a.startDate == null || a.endDate == null || b.startDate == null || b.endDate == null) {
            return false;
        }
        return !a.startDate.isAfter(b.endDate) && !b.startDate.isAfter(a.endDate);
    }
}
