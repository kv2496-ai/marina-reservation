package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Implements the three "Resolve" actions on the Resolve tab: hard-conflict resolution, oversized
 * -for-berth (LOA) resolution, and missing-vital-data resolution. See ISSUES.md for the full
 * decision tree this was built from and the assumptions made where the spec was ambiguous.
 *
 * A deliberate limit applies to all three: nothing here acts on a reservation that's already in
 * the past (endDate before today) — you can't un-happen history, so those are surfaced as
 * informational only.
 */
@Service
public class ResolveService {

    private final JsonCollectionStore<ValidationFlag> flagStore;
    private final JsonCollectionStore<Reservation> reservationStore;
    private final JsonCollectionStore<Vessel> vesselStore;
    private final JsonCollectionStore<Berth> berthStore;
    private final ReservationService reservationService;
    private final WaitlistService waitlistService;
    private final NotificationService notificationService;

    public ResolveService(JsonCollectionStore<ValidationFlag> flagStore,
                           JsonCollectionStore<Reservation> reservationStore,
                           JsonCollectionStore<Vessel> vesselStore,
                           JsonCollectionStore<Berth> berthStore,
                           ReservationService reservationService,
                           WaitlistService waitlistService,
                           NotificationService notificationService) {
        this.flagStore = flagStore;
        this.reservationStore = reservationStore;
        this.vesselStore = vesselStore;
        this.berthStore = berthStore;
        this.reservationService = reservationService;
        this.waitlistService = waitlistService;
        this.notificationService = notificationService;
    }

    // ---------- Hard conflicts (DOUBLE_BOOKING) ----------

    public ResolveResult resolveConflict(String flagId, String user) {
        ValidationFlag flag = requireFlag(flagId, ValidationType.DOUBLE_BOOKING);
        if (flag.severity != ValidationSeverity.HARD_CONFLICT) {
            // e.g. one side was already bumped to PENDING by a previous resolve — the overlap is
            // now just a warning, not something this action is meant to touch again.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This overlap is no longer a hard conflict (already resolved, or one side isn't Confirmed) — nothing to do.");
        }
        Reservation a = requireReservation(flag.reservationId);
        Reservation b = requireReservation(flag.relatedReservationId);

        if (isPast(a) && isPast(b)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Both reservations are in the past — historical conflicts aren't auto-resolved.");
        }
        if (a.kind != ReservationKind.VESSEL || b.kind != ReservationKind.VESSEL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "One side of this conflict isn't a vessel reservation (e.g. maintenance/event) — "
                            + "the automated resolution only handles vessel-vs-vessel conflicts. Resolve manually.");
        }

        Vessel va = a.vesselId == null ? null : vesselStore.findById(a.vesselId).orElse(null);
        Vessel vb = b.vesselId == null ? null : vesselStore.findById(b.vesselId).orElse(null);
        boolean hasContactA = hasContact(va);
        boolean hasContactB = hasContact(vb);

        if (!hasContactA && !hasContactB) {
            reservationService.cancel(a.id, user);
            reservationService.cancel(b.id, user);
            waitlistService.checkForMatches();
            notificationService.create(NotificationType.CONFLICT_BOTH_CANCELED_NO_CONTACT, a.vesselId, a.vesselNameSnapshot, a.id,
                    "Both vessels in this conflict have no phone/email on file. Both reservations were "
                            + "canceled rather than guessing which to keep — needs manual review.");
            notificationService.create(NotificationType.CONFLICT_BOTH_CANCELED_NO_CONTACT, b.vesselId, b.vesselNameSnapshot, b.id,
                    "Both vessels in this conflict have no phone/email on file. Both reservations were "
                            + "canceled rather than guessing which to keep — needs manual review.");
            return new ResolveResult("BOTH_CANCELED", "Neither vessel had contact info on file — both reservations "
                    + "were canceled and flagged for manual review.");
        }
        if (!hasContactA) {
            return cancelForNoContact(a, b, user);
        }
        if (!hasContactB) {
            return cancelForNoContact(b, a, user);
        }

        // Both reachable: earlier-booked (by when it was entered into the system) keeps the berth.
        Reservation keeper = a.createdAt.isBefore(b.createdAt) ? a : b;
        Reservation loser = keeper == a ? b : a;
        Vessel loserVessel = keeper == a ? vb : va;

        return bumpWithSwitchOrWaitlist(loser, loserVessel, keeper);
    }

    private ResolveResult cancelForNoContact(Reservation noContactSide, Reservation keptSide, String user) {
        reservationService.cancel(noContactSide.id, user);
        waitlistService.checkForMatches();
        notificationService.create(NotificationType.CANCELED_NO_CONTACT, noContactSide.vesselId, noContactSide.vesselNameSnapshot,
                noContactSide.id, "Reservation for \"" + noContactSide.vesselNameSnapshot + "\" was canceled to resolve a "
                        + "double-booking — no phone or email on file to offer a berth switch or waitlist spot.");
        return new ResolveResult("CANCELED_NO_CONTACT",
                String.format("\"%s\" was canceled (no contact info on file). \"%s\" keeps the berth.",
                        noContactSide.vesselNameSnapshot, keptSide.vesselNameSnapshot));
    }

    private ResolveResult bumpWithSwitchOrWaitlist(Reservation loser, Vessel loserVessel, Reservation keeper) {
        List<Berth> alternatives = findFittingBerths(loserVessel, loser.startDate, loser.endDate, loser.berthId);

        if (!alternatives.isEmpty()) {
            List<String> names = alternatives.stream().map(bth -> bth.name).toList();
            markPendingContactResponse(loser, "a berth-switch offer (bumped by an earlier-booked conflicting reservation)");
            notificationService.create(NotificationType.SWITCH_OFFERED, loser.vesselId, loser.vesselNameSnapshot, loser.id,
                    String.format("\"%s\" was bumped from \"%s\" by an earlier booking. Offer them one of: %s. Reservation "
                                    + "status set to PENDING until you hear back.",
                            loser.vesselNameSnapshot, loser.berthNameSnapshot, String.join(", ", names)));
            return new ResolveResult("SWITCH_OFFERED",
                    String.format("\"%s\" keeps the berth. \"%s\" was set to PENDING — %d alternative berth(s) available "
                                    + "to offer its contact.", keeper.vesselNameSnapshot, loser.vesselNameSnapshot, alternatives.size()))
                    .withAlternatives(names);
        } else {
            Berth originBerth = berthStore.findById(loser.berthId).orElse(null);
            reservationService.cancel(loser.id, "resolve-engine");
            waitlistService.add(loserVessel, loser.startDate, loser.endDate, originBerth, loser.id,
                    "Bumped from a double-booking conflict (earlier reservation kept the berth).");
            notificationService.create(NotificationType.WAITLISTED, loser.vesselId, loser.vesselNameSnapshot, loser.id,
                    String.format("\"%s\" was bumped from \"%s\" with no alternative berth available right now and moved "
                                    + "to the waitlist. Let them know.", loser.vesselNameSnapshot, loser.berthNameSnapshot));
            return new ResolveResult("WAITLISTED",
                    String.format("\"%s\" keeps the berth. \"%s\" had no alternative available — canceled and moved to "
                            + "the waitlist.", keeper.vesselNameSnapshot, loser.vesselNameSnapshot));
        }
    }

    // ---------- LOA exceeds berth ----------

    public List<Berth> loaAlternatives(String flagId) {
        ValidationFlag flag = requireFlag(flagId, ValidationType.LOA_EXCEEDS_BERTH);
        Reservation r = requireReservation(flag.reservationId);
        guardNotPast(r);
        Vessel vessel = r.vesselId == null ? null : vesselStore.findById(r.vesselId).orElse(null);
        return findFittingBerths(vessel, r.startDate, r.endDate, r.berthId);
    }

    public ResolveResult loaNotify(String flagId) {
        ValidationFlag flag = requireFlag(flagId, ValidationType.LOA_EXCEEDS_BERTH);
        Reservation r = requireReservation(flag.reservationId);
        guardNotPast(r);
        List<Berth> alternatives = loaAlternatives(flagId);
        if (alternatives.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No alternatives available — use waitlist instead.");
        }
        List<String> names = alternatives.stream().map(b -> b.name).toList();
        notificationService.create(NotificationType.SWITCH_OFFERED, r.vesselId, r.vesselNameSnapshot, r.id,
                String.format("\"%s\" doesn't fit at \"%s\". Offer them one of: %s.", r.vesselNameSnapshot,
                        r.berthNameSnapshot, String.join(", ", names)));
        return new ResolveResult("SWITCH_OFFERED", "Logged a notification listing " + alternatives.size()
                + " alternative berth(s) — contact " + r.vesselNameSnapshot + " and update the reservation once they decide.")
                .withAlternatives(names);
    }

    public ResolveResult loaWaitlist(String flagId, String user) {
        ValidationFlag flag = requireFlag(flagId, ValidationType.LOA_EXCEEDS_BERTH);
        Reservation r = requireReservation(flag.reservationId);
        guardNotPast(r);
        if (!loaAlternatives(flagId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Alternative berths exist — offer a switch instead of waitlisting.");
        }
        Vessel vessel = r.vesselId == null ? null : vesselStore.findById(r.vesselId).orElse(null);
        Berth originBerth = berthStore.findById(r.berthId).orElse(null);
        reservationService.cancel(r.id, user);
        waitlistService.add(vessel, r.startDate, r.endDate, originBerth, r.id,
                "Bumped: vessel LOA exceeds \"" + r.berthNameSnapshot + "\" and no alternative berth fit.");
        notificationService.create(NotificationType.WAITLISTED, r.vesselId, r.vesselNameSnapshot, r.id,
                String.format("\"%s\" doesn't fit at \"%s\" and no alternative was available — canceled and moved to "
                        + "the waitlist. Let them know.", r.vesselNameSnapshot, r.berthNameSnapshot));
        return new ResolveResult("WAITLISTED", "No alternative berth fit — reservation canceled and moved to the waitlist.");
    }

    // ---------- Missing vital vessel data ----------

    public ResolveResult resolveMissingData(String flagId, String user) {
        ValidationFlag flag = requireFlag(flagId, ValidationType.MISSING_VESSEL_INFO);
        Reservation r = requireReservation(flag.reservationId);
        guardNotPast(r);

        notificationService.create(NotificationType.CANCELED_INSUFFICIENT_DATA, r.vesselId, r.vesselNameSnapshot, r.id,
                String.format("Not enough information on file for \"%s\" to keep this reservation "
                        + "(missing LOA and/or contact details) — reservation canceled.", r.vesselNameSnapshot));
        reservationService.cancel(r.id, user);
        waitlistService.checkForMatches();
        return new ResolveResult("CANCELED_INSUFFICIENT_DATA",
                "Not enough information on file for \"" + r.vesselNameSnapshot + "\" — reservation canceled.");
    }

    // ---------- shared helpers ----------

    private void markPendingContactResponse(Reservation loser, String reason) {
        Reservation updated = new Reservation();
        updated.kind = loser.kind;
        updated.title = loser.title;
        updated.vesselId = loser.vesselId;
        updated.berthId = loser.berthId;
        updated.startDate = loser.startDate;
        updated.endDate = loser.endDate;
        updated.startTime = loser.startTime;
        updated.endTime = loser.endTime;
        updated.status = ReservationStatus.PENDING;
        updated.recurring = loser.recurring;
        updated.recurrenceRule = loser.recurrenceRule;
        updated.recurrenceGroupId = loser.recurrenceGroupId;
        updated.raftingApproved = loser.raftingApproved;
        updated.source = loser.source;
        updated.importMeta = loser.importMeta;
        updated.category = loser.category;
        updated.notes = ((loser.notes == null || loser.notes.isBlank()) ? "" : loser.notes + " ")
                + "Set to PENDING pending contact response to " + reason + ".";
        reservationService.update(loser.id, updated, "resolve-engine");
    }

    private List<Berth> findFittingBerths(Vessel vessel, LocalDate start, LocalDate end, String excludeBerthId) {
        if (vessel == null || vessel.loaFt == null) {
            return List.of(); // can't safely claim a fit without a known LOA
        }
        List<Reservation> all = reservationStore.findAll();
        return berthStore.findAll().stream()
                .filter(b -> !b.id.equals(excludeBerthId))
                .filter(b -> b.status == BerthStatus.ACTIVE)
                .filter(b -> b.lengthFt != null && b.lengthFt >= vessel.loaFt)
                .filter(b -> all.stream().noneMatch(r -> b.id.equals(r.berthId) && r.status == ReservationStatus.CONFIRMED
                        && overlaps(r, start, end)))
                .toList();
    }

    private boolean overlaps(Reservation r, LocalDate start, LocalDate end) {
        if (r.startDate == null || r.endDate == null) return false;
        return !r.startDate.isAfter(end) && !start.isAfter(r.endDate);
    }

    private boolean hasContact(Vessel v) {
        if (v == null) return false;
        boolean hasPhone = v.phone != null && !v.phone.isBlank();
        boolean hasEmail = v.email != null && !v.email.isBlank();
        return hasPhone || hasEmail;
    }

    private boolean isPast(Reservation r) {
        return r.endDate != null && r.endDate.isBefore(LocalDate.now());
    }

    private void guardNotPast(Reservation r) {
        if (isPast(r)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This reservation is in the past — historical records aren't auto-resolved.");
        }
    }

    private ValidationFlag requireFlag(String flagId, ValidationType expectedType) {
        ValidationFlag flag = flagStore.findById(flagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag not found: " + flagId));
        if (flag.type != expectedType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flag " + flagId + " is not a " + expectedType + " flag.");
        }
        return flag;
    }

    private Reservation requireReservation(String id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flag has no linked reservation.");
        }
        return reservationStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found: " + id));
    }
}
