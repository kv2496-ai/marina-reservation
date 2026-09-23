package edu.whoi.marina.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards against accidental duplicate submissions (e.g. a double-click or a retried request)
 * without needing a database. The client sends a fresh idempotency key each time a "new
 * reservation" form is opened; if the same key arrives twice, the second call gets back the
 * first call's result instead of creating a second record.
 */
@Service
public class IdempotencyService {

    private record Entry(Object result, Instant seenAt) {
    }

    private final Map<String, Entry> seen = new ConcurrentHashMap<>();
    private static final long TTL_SECONDS = 600;

    @SuppressWarnings("unchecked")
    public <T> T executeOnce(String idempotencyKey, java.util.function.Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        evictExpired();
        Entry existing = seen.get(idempotencyKey);
        if (existing != null) {
            return (T) existing.result();
        }
        synchronized (this) {
            existing = seen.get(idempotencyKey);
            if (existing != null) {
                return (T) existing.result();
            }
            T result = action.get();
            seen.put(idempotencyKey, new Entry(result, Instant.now()));
            return result;
        }
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(TTL_SECONDS);
        seen.entrySet().removeIf(e -> e.getValue().seenAt().isBefore(cutoff));
    }
}
