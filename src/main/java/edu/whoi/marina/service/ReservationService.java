package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ReservationService {

    private final JsonCollectionStore<Reservation> store;
    private final JsonCollectionStore<Berth> berthStore;
    private final JsonCollectionStore<Vessel> vesselStore;
    private final ValidationService validationService;
    private final AuditService auditService;
    private final RecurrenceService recurrenceService;
    private final IdempotencyService idempotencyService;

    public ReservationService(JsonCollectionStore<Reservation> store,
                               JsonCollectionStore<Berth> berthStore,
                               JsonCollectionStore<Vessel> vesselStore,
                               ValidationService validationService,
                               AuditService auditService,
                               RecurrenceService recurrenceService,
                               IdempotencyService idempotencyService) {
        this.store = store;
        this.berthStore = berthStore;
        this.vesselStore = vesselStore;
        this.validationService = validationService;
        this.auditService = auditService;
        this.recurrenceService = recurrenceService;
        this.idempotencyService = idempotencyService;
    }

    public List<Reservation> findAll() {
        return store.findAll();
    }

    public Reservation findByIdOrThrow(String id) {
        return store.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found: " + id));
    }

    public List<Reservation> search(String berthId, String vesselId, ReservationKind kind, ReservationStatus status,
                                     LocalDate from, LocalDate to, String query) {
        String needle = (query == null || query.isBlank()) ? null : query.trim().toLowerCase();
        return store.findAll().stream()
                .filter(r -> berthId == null || berthId.equals(r.berthId))
                .filter(r -> vesselId == null || vesselId.equals(r.vesselId))
                .filter(r -> kind == null || kind == r.kind)
                .filter(r -> status == null || status == r.status)
                .filter(r -> from == null || (r.endDate != null && !r.endDate.isBefore(from)))
                .filter(r -> to == null || (r.startDate != null && !r.startDate.isAfter(to)))
                .filter(r -> needle == null || matchesText(r, needle))
                .toList();
    }

    private boolean matchesText(Reservation r, String needle) {
        return containsIgnoreCase(r.title, needle)
                || containsIgnoreCase(r.vesselNameSnapshot, needle)
                || containsIgnoreCase(r.berthNameSnapshot, needle)
                || containsIgnoreCase(r.notes, needle)
                || containsIgnoreCase(r.category, needle);
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    public Reservation create(Reservation incoming, String user, String idempotencyKey) {
        String namespacedKey = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : "create:" + idempotencyKey;
        return idempotencyService.executeOnce(namespacedKey, () -> doCreate(incoming, user));
    }

    private Reservation doCreate(Reservation incoming, String user) {
        prepareForSave(incoming, user, true);
        Reservation saved = store.save(incoming);
        auditService.record(saved.id, user, "CREATE", null, saved);
        validationService.revalidateBerth(saved.berthId);
        return saved;
    }

    public List<Reservation> createRecurringSeries(Reservation template, String user, String idempotencyKey) {
        String namespacedKey = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : "createSeries:" + idempotencyKey;
        return idempotencyService.executeOnce(namespacedKey, () -> {
            List<Reservation> occurrences = recurrenceService.expand(template);
            Set<String> touchedBerths = occurrences.stream().map(r -> r.berthId).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            for (Reservation occ : occurrences) {
                prepareForSave(occ, user, true);
            }
            store.saveAll(occurrences);
            for (Reservation occ : occurrences) {
                auditService.record(occ.id, user, "CREATE_RECURRING", null, occ);
            }
            touchedBerths.forEach(validationService::revalidateBerth);
            return occurrences;
        });
    }

    public Reservation update(String id, Reservation incoming, String user) {
        Reservation existing = findByIdOrThrow(id);
        Reservation before = shallowCopy(existing);
        String oldBerthId = existing.berthId;

        incoming.id = id;
        incoming.createdAt = existing.createdAt;
        incoming.createdBy = existing.createdBy;
        prepareForSave(incoming, user, false);

        Reservation saved = store.save(incoming);
        auditService.record(id, user, "UPDATE", before, saved);

        if (oldBerthId != null && !oldBerthId.equals(saved.berthId)) {
            validationService.revalidateBerth(oldBerthId);
        }
        validationService.revalidateBerth(saved.berthId);
        return saved;
    }

    public Reservation cancel(String id, String user) {
        Reservation existing = findByIdOrThrow(id);
        Reservation before = shallowCopy(existing);
        existing.status = ReservationStatus.CANCELED;
        existing.updatedAt = java.time.Instant.now();
        existing.updatedBy = user;
        Reservation saved = store.save(existing);
        auditService.record(id, user, "CANCEL", before, saved);
        // A canceled reservation is excluded from revalidateBerth's own recompute (it's no longer
        // active), so its own now-stale flags would never be cleared without this explicit purge.
        validationService.purgeFlagsForReservation(id);
        validationService.revalidateBerth(saved.berthId);
        return saved;
    }

    public void delete(String id, String user) {
        Reservation existing = findByIdOrThrow(id);
        Reservation before = shallowCopy(existing);
        store.deleteById(id);
        auditService.record(id, user, "DELETE", before, null);
        validationService.purgeFlagsForReservation(id);
        validationService.revalidateBerth(before.berthId);
    }

    private void prepareForSave(Reservation r, String user, boolean isNew) {
        if (isNew) {
            r.id = UUID.randomUUID().toString();
            r.createdAt = java.time.Instant.now();
            r.createdBy = user;
        }
        r.updatedAt = java.time.Instant.now();
        r.updatedBy = user;

        if (r.startDate == null || r.endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate and endDate are required");
        }
        if (r.endDate.isBefore(r.startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate cannot be before startDate");
        }
        if (r.berthId != null) {
            Berth berth = berthStore.findById(r.berthId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown berthId: " + r.berthId));
            r.berthNameSnapshot = berth.name;
        }
        if (r.kind == ReservationKind.VESSEL && r.vesselId != null) {
            Vessel vessel = vesselStore.findById(r.vesselId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown vesselId: " + r.vesselId));
            r.vesselNameSnapshot = vessel.name;
        }
    }

    private Reservation shallowCopy(Reservation r) {
        try {
            return (Reservation) CLONER.readValue(CLONER.writeValueAsString(r), Reservation.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CLONER =
            new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
}
