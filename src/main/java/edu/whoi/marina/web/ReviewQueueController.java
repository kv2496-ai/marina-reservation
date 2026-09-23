package edu.whoi.marina.web;

import edu.whoi.marina.domain.ReviewQueueItem;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/import/review-queue")
public class ReviewQueueController {

    private final JsonCollectionStore<ReviewQueueItem> store;

    public ReviewQueueController(JsonCollectionStore<ReviewQueueItem> store) {
        this.store = store;
    }

    @GetMapping
    public List<ReviewQueueItem> all(@RequestParam(required = false) Boolean resolved) {
        return store.findAll().stream()
                .filter(i -> resolved == null || i.resolved == resolved)
                .toList();
    }

    @PostMapping("/{id}/resolve")
    public ReviewQueueItem resolve(@PathVariable String id) {
        return store.transact(items -> {
            ReviewQueueItem item = items.stream().filter(i -> i.id.equals(id)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review item not found: " + id));
            item.resolved = true;
            return item;
        });
    }
}
