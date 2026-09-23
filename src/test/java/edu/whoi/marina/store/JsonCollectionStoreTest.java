package edu.whoi.marina.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.whoi.marina.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JsonCollectionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void transactRollsBackOnException_noPartialWrite() {
        JsonCollectionStore<Reservation> store = new JsonCollectionStore<>(
                tempDir.resolve("r.json"), new ObjectMapper().registerModule(new JavaTimeModule()), Reservation.class, r -> r.id);

        Reservation r = new Reservation();
        r.id = "keep-me";
        store.save(r);

        try {
            store.transact(list -> {
                list.add(new Reservation());
                throw new RuntimeException("simulated failure");
            });
        } catch (RuntimeException expected) {
            // expected
        }

        assertThat(store.findAll()).hasSize(1);
        assertThat(store.findAll().get(0).id).isEqualTo("keep-me");
    }

    @Test
    void concurrentCreates_neverLoseAWrite() throws InterruptedException {
        JsonCollectionStore<Reservation> store = new JsonCollectionStore<>(
                tempDir.resolve("r.json"), new ObjectMapper().registerModule(new JavaTimeModule()), Reservation.class, r -> r.id);

        int threads = 20;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    try {
                        Reservation r = new Reservation();
                        r.id = UUID.randomUUID().toString();
                        store.save(r);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(errors.get()).isZero();
        List<Reservation> all = store.findAll();
        assertThat(all).hasSize(threads * perThread);
        assertThat(all.stream().map(r -> r.id).distinct().count()).isEqualTo((long) threads * perThread);
    }

    @Test
    void checkThenInsert_underConcurrency_neverDoubleInsertsSameId() throws InterruptedException {
        // Simulates the "reject if a conflicting id already exists, else insert" pattern used for
        // reservation creation — every transact() call is serialized by the store's lock, so this
        // must never end up with two winners.
        JsonCollectionStore<Reservation> store = new JsonCollectionStore<>(
                tempDir.resolve("r.json"), new ObjectMapper().registerModule(new JavaTimeModule()), Reservation.class, r -> r.id);

        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                boolean inserted = store.transact(list -> {
                    boolean exists = list.stream().anyMatch(r -> "singleton".equals(r.id));
                    if (exists) return false;
                    Reservation r = new Reservation();
                    r.id = "singleton";
                    list.add(r);
                    return true;
                });
                if (inserted) successes.incrementAndGet();
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(store.findAll()).hasSize(1);
    }
}
