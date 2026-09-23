package edu.whoi.marina.web;

import edu.whoi.marina.domain.Berth;
import edu.whoi.marina.service.ResolveResult;
import edu.whoi.marina.service.ResolveService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resolve")
public class ResolveController {

    private final ResolveService service;

    public ResolveController(ResolveService service) {
        this.service = service;
    }

    @PostMapping("/conflict/{flagId}")
    public ResolveResult resolveConflict(@PathVariable String flagId,
                                          @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.resolveConflict(flagId, user);
    }

    @GetMapping("/loa/{flagId}/alternatives")
    public List<Berth> loaAlternatives(@PathVariable String flagId) {
        return service.loaAlternatives(flagId);
    }

    @PostMapping("/loa/{flagId}/notify")
    public ResolveResult loaNotify(@PathVariable String flagId) {
        return service.loaNotify(flagId);
    }

    @PostMapping("/loa/{flagId}/waitlist")
    public ResolveResult loaWaitlist(@PathVariable String flagId,
                                      @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.loaWaitlist(flagId, user);
    }

    @PostMapping("/missing-data/{flagId}")
    public ResolveResult resolveMissingData(@PathVariable String flagId,
                                             @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.resolveMissingData(flagId, user);
    }
}
