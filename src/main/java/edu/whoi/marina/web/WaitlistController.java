package edu.whoi.marina.web;

import edu.whoi.marina.domain.Reservation;
import edu.whoi.marina.domain.WaitlistEntry;
import edu.whoi.marina.domain.WaitlistStatus;
import edu.whoi.marina.service.WaitlistService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private final WaitlistService service;

    public WaitlistController(WaitlistService service) {
        this.service = service;
    }

    @GetMapping
    public List<WaitlistEntry> all(@RequestParam(required = false) WaitlistStatus status) {
        return service.findAll(status);
    }

    @PostMapping("/check")
    public void check() {
        service.checkForMatches();
    }

    @PostMapping("/{id}/confirm")
    public Reservation confirm(@PathVariable String id,
                                @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.confirm(id, user);
    }

    @PostMapping("/{id}/cancel")
    public WaitlistEntry cancel(@PathVariable String id) {
        return service.cancel(id);
    }
}
