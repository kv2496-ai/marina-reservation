package edu.whoi.marina.web;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.service.AuditService;
import edu.whoi.marina.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService service;
    private final AuditService auditService;

    public ReservationController(ReservationService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Reservation> search(
            @RequestParam(required = false) String berthId,
            @RequestParam(required = false) String vesselId,
            @RequestParam(required = false) ReservationKind kind,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return service.search(berthId, vesselId, kind, status, from, to, q);
    }

    @GetMapping("/{id}")
    public Reservation one(@PathVariable String id) {
        return service.findByIdOrThrow(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogEntry> audit(@PathVariable String id) {
        return auditService.forReservation(id);
    }

    @PostMapping
    public Reservation create(@Valid @RequestBody Reservation reservation,
                               @RequestHeader(value = "X-User", defaultValue = "staff") String user,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(reservation, user, idempotencyKey);
    }

    @PostMapping("/series")
    public List<Reservation> createSeries(@Valid @RequestBody Reservation template,
                                           @RequestHeader(value = "X-User", defaultValue = "staff") String user,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.createRecurringSeries(template, user, idempotencyKey);
    }

    @PutMapping("/{id}")
    public Reservation update(@PathVariable String id, @Valid @RequestBody Reservation reservation,
                               @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.update(id, reservation, user);
    }

    @PostMapping("/{id}/cancel")
    public Reservation cancel(@PathVariable String id,
                               @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        return service.cancel(id, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id,
                        @RequestHeader(value = "X-User", defaultValue = "staff") String user) {
        service.delete(id, user);
    }
}
