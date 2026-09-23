package edu.whoi.marina.service;

import edu.whoi.marina.domain.Vessel;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class VesselService {

    private final JsonCollectionStore<Vessel> store;

    public VesselService(JsonCollectionStore<Vessel> store) {
        this.store = store;
    }

    public List<Vessel> findAll() {
        return store.findAll();
    }

    public Vessel findByIdOrThrow(String id) {
        return store.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Vessel not found: " + id));
    }

    /** Autocomplete: case-insensitive substring match against name, capped for payload size. */
    public List<Vessel> search(String query) {
        if (query == null || query.isBlank()) {
            return store.findAll().stream()
                    .sorted(Comparator.comparing(v -> v.name == null ? "" : v.name))
                    .limit(50)
                    .toList();
        }
        String needle = Vessel.VesselNames.normalize(query);
        return store.findAll().stream()
                .filter(v -> v.normalizedName != null && v.normalizedName.contains(needle))
                .sorted(Comparator.comparing(v -> v.name == null ? "" : v.name))
                .limit(25)
                .toList();
    }

    public Vessel create(Vessel vessel) {
        vessel.id = UUID.randomUUID().toString();
        vessel.normalizedName = Vessel.VesselNames.normalize(vessel.name);
        return store.save(vessel);
    }

    public Vessel update(String id, Vessel updated) {
        findByIdOrThrow(id);
        updated.id = id;
        updated.normalizedName = Vessel.VesselNames.normalize(updated.name);
        return store.save(updated);
    }

    public void delete(String id) {
        findByIdOrThrow(id);
        store.deleteById(id);
    }

    /** Used by the importer: find an existing vessel by exact normalized-name match, or null. */
    public Vessel findByNormalizedName(String normalizedName) {
        return store.findAll().stream()
                .filter(v -> normalizedName.equals(v.normalizedName))
                .findFirst()
                .orElse(null);
    }
}
