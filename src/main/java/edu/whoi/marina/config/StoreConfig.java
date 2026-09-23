package edu.whoi.marina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(MarinaProperties.class)
public class StoreConfig {

    @Bean
    public JsonCollectionStore<Berth> berthStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "berths.json"), mapper, Berth.class, b -> b.id);
    }

    @Bean
    public JsonCollectionStore<Vessel> vesselStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "vessels.json"), mapper, Vessel.class, v -> v.id);
    }

    @Bean
    public JsonCollectionStore<Reservation> reservationStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "reservations.json"), mapper, Reservation.class, r -> r.id);
    }

    @Bean
    public JsonCollectionStore<ValidationFlag> validationFlagStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "validation-flags.json"), mapper, ValidationFlag.class, f -> f.id);
    }

    @Bean
    public JsonCollectionStore<AuditLogEntry> auditLogStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "audit-log.json"), mapper, AuditLogEntry.class, a -> a.id);
    }

    @Bean
    public JsonCollectionStore<ReviewQueueItem> reviewQueueStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "import-review-queue.json"), mapper, ReviewQueueItem.class, r -> r.id);
    }

    @Bean
    public JsonCollectionStore<Notification> notificationStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "notifications.json"), mapper, Notification.class, n -> n.id);
    }

    @Bean
    public JsonCollectionStore<WaitlistEntry> waitlistStore(MarinaProperties props, ObjectMapper mapper) {
        return new JsonCollectionStore<>(dataFile(props, "waitlist.json"), mapper, WaitlistEntry.class, w -> w.id);
    }

    private Path dataFile(MarinaProperties props, String name) {
        return Path.of(props.getDataDir()).resolve(name);
    }
}
