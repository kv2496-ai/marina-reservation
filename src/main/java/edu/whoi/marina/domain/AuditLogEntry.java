package edu.whoi.marina.domain;

import java.time.Instant;
import java.util.UUID;

public class AuditLogEntry {
    public String id = UUID.randomUUID().toString();
    public String reservationId;
    public Instant timestamp = Instant.now();
    public String user;
    /** CREATE, UPDATE, CANCEL, DELETE */
    public String action;
    public Reservation before;
    public Reservation after;

    public AuditLogEntry() {
    }

    public AuditLogEntry(String reservationId, String user, String action, Reservation before, Reservation after) {
        this.reservationId = reservationId;
        this.user = user;
        this.action = action;
        this.before = before;
        this.after = after;
    }
}
