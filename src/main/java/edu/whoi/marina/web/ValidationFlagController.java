package edu.whoi.marina.web;

import edu.whoi.marina.domain.ValidationFlag;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/validation-flags")
public class ValidationFlagController {

    private final JsonCollectionStore<ValidationFlag> store;

    public ValidationFlagController(JsonCollectionStore<ValidationFlag> store) {
        this.store = store;
    }

    @GetMapping
    public List<ValidationFlag> all(@RequestParam(required = false) Boolean resolved,
                                     @RequestParam(required = false) String reservationId) {
        return store.findAll().stream()
                .filter(f -> resolved == null || f.resolved == resolved)
                .filter(f -> reservationId == null || reservationId.equals(f.reservationId))
                .toList();
    }

    @PostMapping("/{id}/resolve")
    public ValidationFlag resolve(@PathVariable String id,
                                   @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return store.transact(flags -> {
            ValidationFlag flag = flags.stream().filter(f -> f.id.equals(id)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag not found: " + id));
            flag.resolved = true;
            flag.resolvedAt = Instant.now();
            flag.resolvedBy = user;
            return flag;
        });
    }
}
