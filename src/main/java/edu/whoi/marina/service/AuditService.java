package edu.whoi.marina.service;

import edu.whoi.marina.domain.AuditLogEntry;
import edu.whoi.marina.domain.Reservation;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class AuditService {

    private final JsonCollectionStore<AuditLogEntry> store;

    public AuditService(JsonCollectionStore<AuditLogEntry> store) {
        this.store = store;
    }

    public void record(String reservationId, String user, String action, Reservation before, Reservation after) {
        store.save(new AuditLogEntry(reservationId, user, action, before, after));
    }

    public List<AuditLogEntry> forReservation(String reservationId) {
        return store.findAll().stream()
                .filter(e -> reservationId.equals(e.reservationId))
                .sorted(Comparator.comparing((AuditLogEntry e) -> e.timestamp))
                .toList();
    }

    public List<AuditLogEntry> recent(int limit) {
        return store.findAll().stream()
                .sorted(Comparator.comparing((AuditLogEntry e) -> e.timestamp).reversed())
                .limit(limit)
                .toList();
    }
}
