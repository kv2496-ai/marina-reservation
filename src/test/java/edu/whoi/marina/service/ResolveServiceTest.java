package edu.whoi.marina.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolveServiceTest {

    @TempDir
    Path tempDir;

    JsonCollectionStore<Reservation> reservationStore;
    JsonCollectionStore<Vessel> vesselStore;
    JsonCollectionStore<Berth> berthStore;
    JsonCollectionStore<ValidationFlag> flagStore;
    JsonCollectionStore<Notification> notificationStore;
    JsonCollectionStore<WaitlistEntry> waitlistStore;
    JsonCollectionStore<AuditLogEntry> auditStore;

    ValidationService validationService;
    ReservationService reservationService;
    NotificationService notificationService;
    WaitlistService waitlistService;
    ResolveService resolveService;

    Berth berthA, berthB, berthC;
    Vessel reachableEarly, reachableLate, noContact;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        reservationStore = new JsonCollectionStore<>(tempDir.resolve("r.json"), mapper, Reservation.class, r -> r.id);
        vesselStore = new JsonCollectionStore<>(tempDir.resolve("v.json"), mapper, Vessel.class, v -> v.id);
        berthStore = new JsonCollectionStore<>(tempDir.resolve("b.json"), mapper, Berth.class, b -> b.id);
        flagStore = new JsonCollectionStore<>(tempDir.resolve("f.json"), mapper, ValidationFlag.class, f -> f.id);
        notificationStore = new JsonCollectionStore<>(tempDir.resolve("n.json"), mapper, Notification.class, n -> n.id);
        waitlistStore = new JsonCollectionStore<>(tempDir.resolve("w.json"), mapper, WaitlistEntry.class, w -> w.id);
        auditStore = new JsonCollectionStore<>(tempDir.resolve("a.json"), mapper, AuditLogEntry.class, a -> a.id);

        validationService = new ValidationService(reservationStore, flagStore, vesselStore, berthStore);
        AuditService auditService = new AuditService(auditStore);
        RecurrenceService recurrenceService = new RecurrenceService();
        IdempotencyService idempotencyService = new IdempotencyService();
        reservationService = new ReservationService(reservationStore, berthStore, vesselStore, validationService,
                auditService, recurrenceService, idempotencyService);
        notificationService = new NotificationService(notificationStore);
        waitlistService = new WaitlistService(waitlistStore, berthStore, reservationStore, notificationService, validationService);
        resolveService = new ResolveService(flagStore, reservationStore, vesselStore, berthStore, reservationService,
                waitlistService, notificationService);

        berthA = new Berth("Berth A", 100.0);
        berthB = new Berth("Berth B", 100.0);
        berthC = new Berth("Berth C", 50.0); // too short for most test vessels
        berthStore.saveAll(List.of(berthA, berthB, berthC));

        reachableEarly = vesselWithContact("Reachable Early", 50.0, "555-0001", null);
        reachableLate = vesselWithContact("Reachable Late", 50.0, "555-0002", null);
        noContact = new Vessel("No Contact Vessel");
        noContact.loaFt = 50.0;
        vesselStore.saveAll(List.of(reachableEarly, reachableLate, noContact));
    }

    private Vessel vesselWithContact(String name, Double loa, String phone, String email) {
        Vessel v = new Vessel(name);
        v.loaFt = loa;
        v.phone = phone;
        v.email = email;
        return v;
    }

    private Reservation confirmedReservation(Vessel v, Berth berth, LocalDate start, LocalDate end) {
        Reservation r = new Reservation();
        r.id = UUID.randomUUID().toString();
        r.kind = ReservationKind.VESSEL;
        r.vesselId = v.id;
        r.vesselNameSnapshot = v.name;
        r.berthId = berth.id;
        r.berthNameSnapshot = berth.name;
        r.startDate = start;
        r.endDate = end;
        r.status = ReservationStatus.CONFIRMED;
        r.createdAt = java.time.Instant.now();
        reservationStore.save(r);
        return r;
    }

    private ValidationFlag conflictFlagFor(String berthId) {
        validationService.revalidateBerth(berthId);
        return flagStore.findAll().stream()
                .filter(f -> f.type == ValidationType.DOUBLE_BOOKING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No DOUBLE_BOOKING flag found"));
    }

    // ---------- Hard conflict resolution ----------

    @Test
    void bothHaveContact_earlierWins_alternativeBerthOffered() throws InterruptedException {
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = LocalDate.now().plusDays(15);
        Reservation earlier = confirmedReservation(reachableEarly, berthA, start, end);
        Thread.sleep(5); // ensure distinct createdAt ordering
        Reservation later = confirmedReservation(reachableLate, berthA, start, end);

        ValidationFlag flag = conflictFlagFor(berthA.id);
        ResolveResult result = resolveService.resolveConflict(flag.id, "tester");

        assertThat(result.outcome).isEqualTo("SWITCH_OFFERED");
        Reservation keeperReloaded = reservationStore.findById(earlier.id).orElseThrow();
        Reservation loserReloaded = reservationStore.findById(later.id).orElseThrow();
        assertThat(keeperReloaded.status).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(loserReloaded.status).isEqualTo(ReservationStatus.PENDING);

        List<Notification> notes = notificationService.findAll(null);
        assertThat(notes).anyMatch(n -> n.type == NotificationType.SWITCH_OFFERED && n.vesselId.equals(reachableLate.id));
    }

    @Test
    void bothHaveContact_noAlternativeBerth_loserWaitlisted() throws InterruptedException {
        // Fill berth B and C so nothing else fits for the loser's dates.
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = LocalDate.now().plusDays(15);
        Vessel filler1 = vesselWithContact("Filler1", 50.0, "555-1", null);
        Vessel filler2 = vesselWithContact("Filler2", 50.0, "555-2", null);
        vesselStore.saveAll(List.of(filler1, filler2));
        confirmedReservation(filler1, berthB, start, end);
        confirmedReservation(filler2, berthC, start, end); // too short anyway, but occupied too

        Reservation earlier = confirmedReservation(reachableEarly, berthA, start, end);
        Thread.sleep(5);
        Reservation later = confirmedReservation(reachableLate, berthA, start, end);

        ValidationFlag flag = conflictFlagFor(berthA.id);
        ResolveResult result = resolveService.resolveConflict(flag.id, "tester");

        assertThat(result.outcome).isEqualTo("WAITLISTED");
        Reservation loserReloaded = reservationStore.findById(later.id).orElseThrow();
        assertThat(loserReloaded.status).isEqualTo(ReservationStatus.CANCELED);

        List<WaitlistEntry> waiting = waitlistService.findAll(null);
        assertThat(waiting).anyMatch(w -> w.vesselId.equals(reachableLate.id));
    }

    @Test
    void oneVesselMissingContact_isCanceled_otherKeepsBerth() {
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = LocalDate.now().plusDays(15);
        Reservation withContact = confirmedReservation(reachableEarly, berthA, start, end);
        Reservation noContactRes = confirmedReservation(noContact, berthA, start, end);

        ValidationFlag flag = conflictFlagFor(berthA.id);
        ResolveResult result = resolveService.resolveConflict(flag.id, "tester");

        assertThat(result.outcome).isEqualTo("CANCELED_NO_CONTACT");
        assertThat(reservationStore.findById(noContactRes.id).orElseThrow().status).isEqualTo(ReservationStatus.CANCELED);
        assertThat(reservationStore.findById(withContact.id).orElseThrow().status).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void bothVesselsMissingContact_bothCanceled() {
        Vessel noContact2 = new Vessel("No Contact Vessel 2");
        noContact2.loaFt = 50.0;
        vesselStore.save(noContact2);

        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = LocalDate.now().plusDays(15);
        Reservation r1 = confirmedReservation(noContact, berthA, start, end);
        Reservation r2 = confirmedReservation(noContact2, berthA, start, end);

        ValidationFlag flag = conflictFlagFor(berthA.id);
        ResolveResult result = resolveService.resolveConflict(flag.id, "tester");

        assertThat(result.outcome).isEqualTo("BOTH_CANCELED");
        assertThat(reservationStore.findById(r1.id).orElseThrow().status).isEqualTo(ReservationStatus.CANCELED);
        assertThat(reservationStore.findById(r2.id).orElseThrow().status).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    void pastConflict_isRejected() {
        LocalDate start = LocalDate.now().minusDays(20);
        LocalDate end = LocalDate.now().minusDays(15);
        confirmedReservation(reachableEarly, berthA, start, end);
        confirmedReservation(reachableLate, berthA, start, end);

        ValidationFlag flag = conflictFlagFor(berthA.id);
        assertThrows(ResponseStatusException.class, () -> resolveService.resolveConflict(flag.id, "tester"));
    }

    // ---------- LOA resolution ----------

    @Test
    void loaAlternatives_findsFittingBerth_excludingCurrentOne() {
        Vessel bigVessel = vesselWithContact("Big Vessel", 80.0, "555-9", null);
        vesselStore.save(bigVessel);
        LocalDate start = LocalDate.now().plusDays(5);
        LocalDate end = LocalDate.now().plusDays(8);
        Berth tooSmall = new Berth("Tiny", 60.0);
        berthStore.save(tooSmall);
        Reservation r = confirmedReservation(bigVessel, tooSmall, start, end);
        validationService.revalidateBerth(tooSmall.id);

        ValidationFlag flag = flagStore.findAll().stream()
                .filter(f -> f.type == ValidationType.LOA_EXCEEDS_BERTH && f.reservationId.equals(r.id))
                .findFirst().orElseThrow();

        List<Berth> alts = resolveService.loaAlternatives(flag.id);
        assertThat(alts).extracting(b -> b.id).contains(berthA.id, berthB.id).doesNotContain(tooSmall.id);
    }

    @Test
    void loaWaitlist_rejectedWhenAlternativesExist() {
        Vessel bigVessel = vesselWithContact("Big Vessel 2", 80.0, "555-8", null);
        vesselStore.save(bigVessel);
        LocalDate start = LocalDate.now().plusDays(5);
        LocalDate end = LocalDate.now().plusDays(8);
        Berth tooSmall = new Berth("Tiny2", 60.0);
        berthStore.save(tooSmall);
        Reservation r = confirmedReservation(bigVessel, tooSmall, start, end);
        validationService.revalidateBerth(tooSmall.id);
        ValidationFlag flag = flagStore.findAll().stream()
                .filter(f -> f.type == ValidationType.LOA_EXCEEDS_BERTH && f.reservationId.equals(r.id))
                .findFirst().orElseThrow();

        assertThrows(ResponseStatusException.class, () -> resolveService.loaWaitlist(flag.id, "tester"));
    }

    // ---------- Missing vital data ----------

    @Test
    void missingData_cancelsReservationAndNotifies() {
        LocalDate start = LocalDate.now().plusDays(3);
        LocalDate end = LocalDate.now().plusDays(4);
        Reservation r = confirmedReservation(noContact, berthA, start, end);
        ValidationFlag flag = new ValidationFlag(r.id, ValidationSeverity.DATA_QUALITY, ValidationType.MISSING_VESSEL_INFO, "no LOA");
        flagStore.save(flag);

        ResolveResult result = resolveService.resolveMissingData(flag.id, "tester");

        assertThat(result.outcome).isEqualTo("CANCELED_INSUFFICIENT_DATA");
        assertThat(reservationStore.findById(r.id).orElseThrow().status).isEqualTo(ReservationStatus.CANCELED);
        assertThat(notificationService.findAll(null)).anyMatch(n -> n.type == NotificationType.CANCELED_INSUFFICIENT_DATA);
    }

    // ---------- Waitlist matching ----------

    @Test
    void waitlistEntry_getsMatchedThenConfirmedIntoRealReservation() {
        Berth freeBerth = new Berth("Free Berth", 100.0);
        berthStore.save(freeBerth);
        LocalDate start = LocalDate.now().plusDays(20);
        LocalDate end = LocalDate.now().plusDays(22);

        WaitlistEntry entry = waitlistService.add(reachableEarly, start, end, berthA, null, "test");
        assertThat(entry.status).isEqualTo(WaitlistStatus.MATCHED);
        assertThat(entry.matchedBerthId).isIn(berthA.id, berthB.id, berthC.id, freeBerth.id);

        Reservation booked = waitlistService.confirm(entry.id, "tester");
        assertThat(booked.status).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(booked.vesselId).isEqualTo(reachableEarly.id);

        WaitlistEntry reloaded = waitlistStore.findById(entry.id).orElseThrow();
        assertThat(reloaded.status).isEqualTo(WaitlistStatus.FULFILLED);
    }
}
