package edu.whoi.marina.web;

import edu.whoi.marina.domain.ReservationKind;
import edu.whoi.marina.domain.ReservationStatus;
import edu.whoi.marina.service.ExportService;
import edu.whoi.marina.service.ReservationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ReservationService reservationService;
    private final ExportService exportService;

    public ExportController(ReservationService reservationService, ExportService exportService) {
        this.reservationService = reservationService;
        this.exportService = exportService;
    }

    @GetMapping("/reservations.csv")
    public ResponseEntity<byte[]> csv(
            @RequestParam(required = false) String berthId,
            @RequestParam(required = false) String vesselId,
            @RequestParam(required = false) ReservationKind kind,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        var rows = reservationService.search(berthId, vesselId, kind, status, from, to, q);
        String csv = exportService.toCsv(rows);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reservations.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }
}
