package edu.whoi.marina.service;

import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Tracks real startup progress so the frontend can show something more honest than a blank page
 * while the (often cold-started, free-tier-hosted) backend re-imports the historical spreadsheet
 * and re-runs validation — which together can take over a minute. See ISSUES.md.
 */
@Service
public class AppStatusService {

    public enum Phase {
        STARTING, IMPORTING, VALIDATING, READY
    }

    private volatile Phase phase = Phase.STARTING;
    private volatile String message = "Starting up…";
    private volatile int reservationCount = 0;
    private volatile int berthsValidated = 0;
    private volatile int totalBerths = 0;
    private final Instant startedAt = Instant.now();

    public void markImporting(String message) {
        this.phase = Phase.IMPORTING;
        this.message = message;
    }

    public void setReservationCount(int count) {
        this.reservationCount = count;
    }

    public void markValidating(int totalBerths) {
        this.phase = Phase.VALIDATING;
        this.totalBerths = totalBerths;
        this.berthsValidated = 0;
        this.message = "Checking schedule for conflicts…";
    }

    public void incrementBerthsValidated() {
        this.berthsValidated++;
    }

    public void markReady(String message) {
        this.phase = Phase.READY;
        this.message = message;
    }

    public StatusSnapshot snapshot() {
        StatusSnapshot s = new StatusSnapshot();
        s.phase = phase.name();
        s.ready = phase == Phase.READY;
        s.message = message;
        s.reservationCount = reservationCount;
        s.berthsValidated = berthsValidated;
        s.totalBerths = totalBerths;
        s.elapsedMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli();
        return s;
    }

    public static class StatusSnapshot {
        public String phase;
        public boolean ready;
        public String message;
        public int reservationCount;
        public int berthsValidated;
        public int totalBerths;
        public long elapsedMs;
    }
}
