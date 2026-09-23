package edu.whoi.marina.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An in-app record of something staff needs to (or already did) tell a vessel contact.
 * This prototype has no email/SMS integration — this log IS the "notification"; a human
 * still has to actually place the call or send the email using the phone/email on file.
 */
public class Notification {
    public String id = UUID.randomUUID().toString();
    public NotificationType type;
    public String vesselId;
    public String vesselNameSnapshot;
    public String reservationId;
    public String message;
    public Instant createdAt = Instant.now();

    /** Set by staff once they've actually contacted the vessel and know the outcome. */
    public boolean acknowledged = false;
    public String outcome;
    public Instant acknowledgedAt;
    public String acknowledgedBy;

    public Notification() {
    }

    public Notification(NotificationType type, String vesselId, String vesselNameSnapshot, String reservationId, String message) {
        this.type = type;
        this.vesselId = vesselId;
        this.vesselNameSnapshot = vesselNameSnapshot;
        this.reservationId = reservationId;
        this.message = message;
    }
}
