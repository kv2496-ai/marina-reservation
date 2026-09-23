package edu.whoi.marina.web;

import edu.whoi.marina.domain.Notification;
import edu.whoi.marina.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Notification> all(@RequestParam(required = false) Boolean acknowledged) {
        return service.findAll(acknowledged);
    }

    @PostMapping("/{id}/acknowledge")
    public Notification acknowledge(@PathVariable String id,
                                     @RequestParam(required = false) String outcome,
                                     @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.acknowledge(id, outcome, user);
    }
}
