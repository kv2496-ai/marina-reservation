package edu.whoi.marina.domain;

public enum WaitlistStatus {
    WAITING,
    /** A fitting berth was found; waiting on staff to confirm the booking. */
    MATCHED,
    FULFILLED,
    CANCELED
}
