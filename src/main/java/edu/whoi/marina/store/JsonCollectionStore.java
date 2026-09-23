package edu.whoi.marina.store;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A JSON-flat-file backed collection with a single global lock per file, so read-modify-write
 * sequences (e.g. "reject if overlapping, else insert") are atomic even under concurrent HTTP
 * requests. Writes are temp-file-then-rename so a crash mid-write can never corrupt the file that
 * readers see.
 *
 * This is the "database-level validation" boundary for the prototype: nothing outside this class
 * ever reads or writes the file directly, so every mutation goes through {@link #transact}.
 */
public class JsonCollectionStore<T> {

    private final Path filePath;
    private final ObjectMapper mapper;
    private final JavaType listType;
    private final Function<T, String> idExtractor;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonCollectionStore(Path filePath, ObjectMapper mapper, Class<T> elementType, Function<T, String> idExtractor) {
        this.filePath = filePath;
        this.mapper = mapper;
        this.listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, elementType);
        this.idExtractor = idExtractor;
        ensureFileExists();
    }

    private void ensureFileExists() {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            if (!Files.exists(filePath)) {
                Files.writeString(filePath, "[]");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not initialize store file " + filePath, e);
        }
    }

    public List<T> findAll() {
        lock.lock();
        try {
            return Collections.unmodifiableList(readAll());
        } finally {
            lock.unlock();
        }
    }

    public Optional<T> findById(String id) {
        return findAll().stream().filter(t -> id.equals(idExtractor.apply(t))).findFirst();
    }

    public T save(T item) {
        return transact(list -> {
            String id = idExtractor.apply(item);
            list.removeIf(existing -> id.equals(idExtractor.apply(existing)));
            list.add(item);
            return item;
        });
    }

    public void saveAll(List<T> items) {
        transact(list -> {
            for (T item : items) {
                String id = idExtractor.apply(item);
                list.removeIf(existing -> id.equals(idExtractor.apply(existing)));
                list.add(item);
            }
            return null;
        });
    }

    public boolean deleteById(String id) {
        return transact(list -> list.removeIf(existing -> id.equals(idExtractor.apply(existing))));
    }

    /**
     * Runs {@code tx} against the full in-memory list while holding the store's lock, then
     * persists the (possibly mutated) list — unless {@code tx} throws, in which case nothing is
     * written and the exception propagates to the caller.
     */
    public <R> R transact(StoreTransaction<T, R> tx) {
        lock.lock();
        try {
            List<T> list = readAll();
            R result = tx.apply(list);
            writeAll(list);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private List<T> readAll() {
        try {
            if (Files.size(filePath) == 0) {
                return new ArrayList<>();
            }
            List<T> list = mapper.readValue(filePath.toFile(), listType);
            return list == null ? new ArrayList<>() : list;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read store file " + filePath, e);
        }
    }

    private void writeAll(List<T> list) {
        try {
            Path tmp = Files.createTempFile(filePath.getParent(), filePath.getFileName().toString(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), list);
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write store file " + filePath, e);
        }
    }
}
