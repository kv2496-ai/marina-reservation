package edu.whoi.marina.web;

import edu.whoi.marina.domain.Berth;
import edu.whoi.marina.service.BerthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/berths")
public class BerthController {

    private final BerthService service;

    public BerthController(BerthService service) {
        this.service = service;
    }

    @GetMapping
    public List<Berth> all() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Berth one(@PathVariable String id) {
        return service.findByIdOrThrow(id);
    }

    @PostMapping
    public Berth create(@Valid @RequestBody Berth berth) {
        return service.create(berth);
    }

    @PutMapping("/{id}")
    public Berth update(@PathVariable String id, @Valid @RequestBody Berth berth) {
        return service.update(id, berth);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
