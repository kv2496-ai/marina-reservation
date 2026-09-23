package edu.whoi.marina.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationServiceTest {

    @TempDir
    Path tempDir;

    JsonCollectionStore<Reservation> reservationStore;
    JsonCollectionStore<ValidationFlag> flagStore;
    JsonCollectionStore<Vessel> vesselStore;
    JsonCollectionStore<Berth> berthStore;
    ValidationService validationService;
    Berth berth;
    Vessel vesselA;
    Vessel vesselB;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        reservationStore = new JsonCollectionStore<>(tempDir.resolve("reservations.json"), mapper, Reservation.class, r -> r.id);
        flagStore = new JsonCollectionStore<>(tempDir.resolve("flags.json"), mapper, ValidationFlag.class, f -> f.id);
        vesselStore = new JsonCollectionStore<>(tempDir.resolve("vessels.json"), mapper, Vessel.class, v -> v.id);
        berthStore = new JsonCollectionStore<>(tempDir.resolve("berths.json"), mapper, Berth.class, b -> b.id);
        validationService = new ValidationService(reservationStore, flagStore, vesselStore, berthStore);

        berth = new Berth("Test Berth", 100.0);
        berthStore.save(berth);

        vesselA = new Vessel("Vessel A");
        vesselA.loaFt = 50.0;
        vesselStore.save(vesselA);

        vesselB = new Vessel("Vessel B");
        vesselB.loaFt = 50.0;
        vesselStore.save(vesselB);
    }

    private Reservation reservation(Vessel v, LocalDate start, LocalDate end, ReservationStatus status, boolean rafting) {
        Reservation r = new Reservation();
        r.id = UUID.randomUUID().toString();
        r.kind = ReservationKind.VESSEL;
        r.vesselId = v.id;
        r.vesselNameSnapshot = v.name;
        r.berthId = berth.id;
        r.startDate = start;
        r.endDate = end;
        r.status = status;
        r.raftingApproved = rafting;
        return r;
    }

    private List<ValidationFlag> flagsFor(String reservationId) {
        return flagStore.findAll().stream().filter(f -> f.reservationId.equals(reservationId)).toList();
    }

    @Test
    void exactOverlap_bothConfirmed_isHardConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 5), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 5), ReservationStatus.CONFIRMED, false);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(a.id)).anyMatch(f -> f.type == ValidationType.DOUBLE_BOOKING && f.severity == ValidationSeverity.HARD_CONFLICT);
        assertThat(flagsFor(b.id)).anyMatch(f -> f.type == ValidationType.DOUBLE_BOOKING && f.severity == ValidationSeverity.HARD_CONFLICT);
    }

    @Test
    void partialOverlap_bothConfirmed_isHardConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 10), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 2, 8), LocalDate.of(2030, 2, 15), ReservationStatus.CONFIRMED, false);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(a.id)).anyMatch(f -> f.severity == ValidationSeverity.HARD_CONFLICT);
    }

    @Test
    void adjacentNonOverlappingDates_noConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 5), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 3, 6), LocalDate.of(2030, 3, 10), ReservationStatus.CONFIRMED, false);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(a.id)).noneMatch(f -> f.type == ValidationType.DOUBLE_BOOKING);
        assertThat(flagsFor(b.id)).noneMatch(f -> f.type == ValidationType.DOUBLE_BOOKING);
    }

    @Test
    void overlap_withOnePending_isWarningNotHardConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 5), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 4, 3), LocalDate.of(2030, 4, 8), ReservationStatus.PENDING, false);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        List<ValidationFlag> aFlags = flagsFor(a.id);
        assertThat(aFlags).anyMatch(f -> f.type == ValidationType.DOUBLE_BOOKING && f.severity == ValidationSeverity.WARNING);
        assertThat(aFlags).noneMatch(f -> f.type == ValidationType.DOUBLE_BOOKING && f.severity == ValidationSeverity.HARD_CONFLICT);
    }

    @Test
    void overlap_bothRaftingApproved_isWarningNotHardConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 5, 1), LocalDate.of(2030, 5, 5), ReservationStatus.CONFIRMED, true);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 5, 3), LocalDate.of(2030, 5, 8), ReservationStatus.CONFIRMED, true);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        List<ValidationFlag> aFlags = flagsFor(a.id);
        assertThat(aFlags).anyMatch(f -> f.type == ValidationType.DOUBLE_BOOKING && f.severity == ValidationSeverity.WARNING);
        assertThat(aFlags).noneMatch(f -> f.severity == ValidationSeverity.HARD_CONFLICT);
    }

    @Test
    void canceledReservation_doesNotConflict() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 6, 1), LocalDate.of(2030, 6, 5), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 6, 3), LocalDate.of(2030, 6, 8), ReservationStatus.CANCELED, false);
        reservationStore.save(a);
        reservationStore.save(b);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(a.id)).noneMatch(f -> f.type == ValidationType.DOUBLE_BOOKING);
    }

    @Test
    void loaExceedsBerthLength_isWarning() {
        Vessel bigVessel = new Vessel("Big Vessel");
        bigVessel.loaFt = 150.0; // berth is 100'
        vesselStore.save(bigVessel);

        Reservation r = reservation(bigVessel, LocalDate.of(2030, 7, 1), LocalDate.of(2030, 7, 5), ReservationStatus.CONFIRMED, false);
        reservationStore.save(r);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(r.id)).anyMatch(f -> f.type == ValidationType.LOA_EXCEEDS_BERTH && f.severity == ValidationSeverity.WARNING);
    }

    @Test
    void loaWithinBerthLength_noWarning() {
        Reservation r = reservation(vesselA, LocalDate.of(2030, 8, 1), LocalDate.of(2030, 8, 5), ReservationStatus.CONFIRMED, false);
        reservationStore.save(r);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(r.id)).noneMatch(f -> f.type == ValidationType.LOA_EXCEEDS_BERTH);
    }

    @Test
    void missingVesselLoa_flagsDataQuality() {
        Vessel noLoa = new Vessel("Mystery Vessel");
        vesselStore.save(noLoa);

        Reservation r = reservation(noLoa, LocalDate.of(2030, 9, 1), LocalDate.of(2030, 9, 5), ReservationStatus.CONFIRMED, false);
        reservationStore.save(r);

        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(r.id)).anyMatch(f -> f.type == ValidationType.MISSING_VESSEL_INFO && f.severity == ValidationSeverity.DATA_QUALITY);
    }

    @Test
    void missingBerthLength_flagsDataQuality() {
        Berth noLengthBerth = new Berth("No Length Berth", null);
        berthStore.save(noLengthBerth);

        Reservation r = reservation(vesselA, LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 5), ReservationStatus.CONFIRMED, false);
        r.berthId = noLengthBerth.id;
        reservationStore.save(r);

        validationService.revalidateBerth(noLengthBerth.id);

        assertThat(flagsFor(r.id)).anyMatch(f -> f.type == ValidationType.MISSING_BERTH_INFO && f.severity == ValidationSeverity.DATA_QUALITY);
    }

    @Test
    void deletingOneOfAConflictingPair_clearsBothFlagSets() {
        Reservation a = reservation(vesselA, LocalDate.of(2030, 11, 1), LocalDate.of(2030, 11, 5), ReservationStatus.CONFIRMED, false);
        Reservation b = reservation(vesselB, LocalDate.of(2030, 11, 3), LocalDate.of(2030, 11, 8), ReservationStatus.CONFIRMED, false);
        reservationStore.save(a);
        reservationStore.save(b);
        validationService.revalidateBerth(berth.id);
        assertThat(flagsFor(a.id)).anyMatch(f -> f.severity == ValidationSeverity.HARD_CONFLICT);

        reservationStore.deleteById(a.id);
        validationService.purgeFlagsForReservation(a.id);
        validationService.revalidateBerth(berth.id);

        assertThat(flagsFor(a.id)).isEmpty();
        assertThat(flagsFor(b.id)).noneMatch(f -> f.severity == ValidationSeverity.HARD_CONFLICT);
    }
}
