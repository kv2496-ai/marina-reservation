package edu.whoi.marina.service;

import edu.whoi.marina.domain.Notification;
import edu.whoi.marina.domain.NotificationType;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The "notification" system for this prototype: there is no email/SMS integration, so this is
 * an in-app log staff read and act on themselves, using the phone/email already on the vessel
 * record. See ISSUES.md for why that's the deliberate scope here.
 */
@Service
public class NotificationService {

    private final JsonCollectionStore<Notification> store;

    public NotificationService(JsonCollectionStore<Notification> store) {
        this.store = store;
    }

    public Notification create(NotificationType type, String vesselId, String vesselName, String reservationId, String message) {
        return store.save(new Notification(type, vesselId, vesselName, reservationId, message));
    }

    public List<Notification> findAll(Boolean acknowledged) {
        return store.findAll().stream()
                .filter(n -> acknowledged == null || n.acknowledged == acknowledged)
                .sorted(Comparator.comparing((Notification n) -> n.createdAt).reversed())
                .toList();
    }

    public Notification acknowledge(String id, String outcome, String user) {
        return store.transact(list -> {
            Notification n = list.stream().filter(x -> x.id.equals(id)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found: " + id));
            n.acknowledged = true;
            n.outcome = outcome;
            n.acknowledgedAt = Instant.now();
            n.acknowledgedBy = user;
            return n;
        });
    }
}
