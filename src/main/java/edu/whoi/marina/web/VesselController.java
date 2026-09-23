package edu.whoi.marina.web;

import edu.whoi.marina.domain.Vessel;
import edu.whoi.marina.service.VesselService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vessels")
public class VesselController {

    private final VesselService service;

    public VesselController(VesselService service) {
        this.service = service;
    }

    @GetMapping
    public List<Vessel> search(@RequestParam(required = false) String q) {
        return service.search(q);
    }

    @GetMapping("/{id}")
    public Vessel one(@PathVariable String id) {
        return service.findByIdOrThrow(id);
    }

    @PostMapping
    public Vessel create(@Valid @RequestBody Vessel vessel) {
        return service.create(vessel);
    }

    @PutMapping("/{id}")
    public Vessel update(@PathVariable String id, @Valid @RequestBody Vessel vessel) {
        return service.update(id, vessel);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
