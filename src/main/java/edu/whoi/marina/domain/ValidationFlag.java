package edu.whoi.marina.domain;

import java.time.Instant;
import java.util.UUID;

public class ValidationFlag {
    public String id = UUID.randomUUID().toString();
    public String reservationId;
    /** Set for DOUBLE_BOOKING flags: the other reservation involved. */
    public String relatedReservationId;
    public ValidationSeverity severity;
    public ValidationType type;
    public String message;
    public Instant detectedAt = Instant.now();
    public boolean resolved = false;
    public Instant resolvedAt;
    public String resolvedBy;

    public ValidationFlag() {
    }

    public ValidationFlag(String reservationId, ValidationSeverity severity, ValidationType type, String message) {
        this.reservationId = reservationId;
        this.severity = severity;
        this.type = type;
        this.message = message;
    }
}
