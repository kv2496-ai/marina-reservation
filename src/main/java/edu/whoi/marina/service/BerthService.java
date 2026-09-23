package edu.whoi.marina.service;

import edu.whoi.marina.domain.Berth;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@Service
public class BerthService {

    private final JsonCollectionStore<Berth> store;
    private final ValidationService validationService;

    public BerthService(JsonCollectionStore<Berth> store, ValidationService validationService) {
        this.store = store;
        this.validationService = validationService;
    }

    public List<Berth> findAll() {
        return store.findAll();
    }

    public Berth findByIdOrThrow(String id) {
        return store.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Berth not found: " + id));
    }

    public Berth create(Berth berth) {
        berth.id = java.util.UUID.randomUUID().toString();
        Berth saved = store.save(berth);
        validationService.revalidateBerth(saved.id);
        return saved;
    }

    public Berth update(String id, Berth updated) {
        findByIdOrThrow(id);
        updated.id = id;
        Berth saved = store.save(updated);
        validationService.revalidateBerth(id);
        return saved;
    }

    public void delete(String id) {
        findByIdOrThrow(id);
        store.deleteById(id);
    }
}
