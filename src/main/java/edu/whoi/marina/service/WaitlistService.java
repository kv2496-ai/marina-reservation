package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Vessels bumped by conflict/LOA resolution with nowhere to go land here, keyed to a fixed
 * LOA + date range ("its specifications"). Matching is checked whenever berth capacity might
 * have changed (a cancel/delete) and can also be triggered manually; a match only marks the
 * entry MATCHED and logs a notification — staff finalizes the actual booking themselves via
 * {@link #confirm}, consistent with there being no automated way to get the vessel's consent.
 */
@Service
public class WaitlistService {

    private final JsonCollectionStore<WaitlistEntry> store;
    private final JsonCollectionStore<Berth> berthStore;
    private final JsonCollectionStore<Reservation> reservationStore;
    private final NotificationService notificationService;
    private final ValidationService validationService;

    public WaitlistService(JsonCollectionStore<WaitlistEntry> store,
                            JsonCollectionStore<Berth> berthStore,
                            JsonCollectionStore<Reservation> reservationStore,
                            NotificationService notificationService,
                            ValidationService validationService) {
        this.store = store;
        this.berthStore = berthStore;
        this.reservationStore = reservationStore;
        this.notificationService = notificationService;
        this.validationService = validationService;
    }

    public List<WaitlistEntry> findAll(WaitlistStatus status) {
        return store.findAll().stream()
                .filter(w -> status == null || w.status == status)
                .sorted(Comparator.comparing((WaitlistEntry w) -> w.createdAt))
                .toList();
    }

    public WaitlistEntry add(Vessel vessel, java.time.LocalDate start, java.time.LocalDate end,
                              Berth originBerth, String originReservationId, String notes) {
        WaitlistEntry entry = new WaitlistEntry();
        entry.vesselId = vessel.id;
        entry.vesselNameSnapshot = vessel.name;
        entry.loaFt = vessel.loaFt;
        entry.startDate = start;
        entry.endDate = end;
        entry.originBerthId = originBerth == null ? null : originBerth.id;
        entry.originBerthNameSnapshot = originBerth == null ? null : originBerth.name;
        entry.originReservationId = originReservationId;
        entry.notes = notes;
        WaitlistEntry saved = store.save(entry);
        checkForMatches();
        return store.findById(saved.id).orElse(saved);
    }

    /** Finds a berth for every WAITING entry, if one exists; called after anything that could
     *  free up capacity (cancel/delete) and exposed for a manual "check now" as well. */
    public void checkForMatches() {
        List<Berth> activeBerths = berthStore.findAll().stream().filter(b -> b.status == BerthStatus.ACTIVE).toList();
        List<Reservation> allReservations = reservationStore.findAll();

        for (WaitlistEntry entry : store.findAll()) {
            if (entry.status != WaitlistStatus.WAITING) continue;

            Berth fit = activeBerths.stream()
                    .filter(b -> !b.id.equals(entry.originBerthId))
                    .filter(b -> b.lengthFt != null && entry.loaFt != null && b.lengthFt >= entry.loaFt)
                    .filter(b -> allReservations.stream().noneMatch(r ->
                            b.id.equals(r.berthId) && r.status == ReservationStatus.CONFIRMED
                                    && overlaps(r, entry.startDate, entry.endDate)))
                    .findFirst().orElse(null);

            if (fit != null) {
                store.transact(list -> {
                    WaitlistEntry e = list.stream().filter(x -> x.id.equals(entry.id)).findFirst().orElse(null);
                    if (e == null || e.status != WaitlistStatus.WAITING) return null;
                    e.status = WaitlistStatus.MATCHED;
                    e.matchedBerthId = fit.id;
                    e.matchedBerthNameSnapshot = fit.name;
                    e.updatedAt = Instant.now();
                    return e;
                });
                notificationService.create(NotificationType.WAITLIST_MATCH_FOUND, entry.vesselId, entry.vesselNameSnapshot, null,
                        String.format("Berth \"%s\" is now available for %s (%s to %s) — contact them to confirm, then use "
                                        + "\"Confirm & book\" on the Waitlist tab.",
                                fit.name, entry.vesselNameSnapshot, entry.startDate, entry.endDate));
            }
        }
    }

    public Reservation confirm(String entryId, String user) {
        WaitlistEntry entry = store.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Waitlist entry not found: " + entryId));
        if (entry.status != WaitlistStatus.MATCHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry has no matched berth to confirm yet.");
        }

        Reservation r = new Reservation();
        r.id = UUID.randomUUID().toString();
        r.kind = ReservationKind.VESSEL;
        r.vesselId = entry.vesselId;
        r.vesselNameSnapshot = entry.vesselNameSnapshot;
        r.berthId = entry.matchedBerthId;
        r.berthNameSnapshot = entry.matchedBerthNameSnapshot;
        r.startDate = entry.startDate;
        r.endDate = entry.endDate;
        r.status = ReservationStatus.CONFIRMED;
        r.source = ReservationSource.MANUAL;
        r.notes = "Fulfilled from waitlist (originally bumped from " + entry.originBerthNameSnapshot + ").";
        r.createdAt = Instant.now();
        r.createdBy = user;
        r.updatedAt = Instant.now();
        r.updatedBy = user;
        reservationStore.save(r);

        String reservationId = r.id;
        store.transact(list -> {
            WaitlistEntry e = list.stream().filter(x -> x.id.equals(entryId)).findFirst().orElse(null);
            if (e != null) {
                e.status = WaitlistStatus.FULFILLED;
                e.fulfilledReservationId = reservationId;
                e.updatedAt = Instant.now();
            }
            return e;
        });

        notificationService.create(NotificationType.WAITLIST_FULFILLED, entry.vesselId, entry.vesselNameSnapshot, r.id,
                String.format("%s booked into \"%s\" (%s to %s) from the waitlist.", entry.vesselNameSnapshot,
                        entry.matchedBerthNameSnapshot, entry.startDate, entry.endDate));

        validationService.revalidateBerth(r.berthId);
        return r;
    }

    public WaitlistEntry cancel(String entryId) {
        return store.transact(list -> {
            WaitlistEntry e = list.stream().filter(x -> x.id.equals(entryId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Waitlist entry not found: " + entryId));
            e.status = WaitlistStatus.CANCELED;
            e.updatedAt = Instant.now();
            return e;
        });
    }

    private boolean overlaps(Reservation r, java.time.LocalDate start, java.time.LocalDate end) {
        if (r.startDate == null || r.endDate == null) return false;
        return !r.startDate.isAfter(end) && !start.isAfter(r.endDate);
    }
}
