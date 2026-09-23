package edu.whoi.marina.domain;

public enum NotificationType {
    /** A better-fitting berth was found and the contact should be asked if they want to switch. */
    SWITCH_OFFERED,
    /** No alternative berth was available, so the reservation was canceled and waitlisted. */
    WAITLISTED,
    /** Reservation auto-canceled because the vessel had no phone/email on file. */
    CANCELED_NO_CONTACT,
    /** Both sides of a conflict lacked contact info, so both were canceled for manual review. */
    CONFLICT_BOTH_CANCELED_NO_CONTACT,
    /** Reservation auto-canceled because the vessel record is missing vital data. */
    CANCELED_INSUFFICIENT_DATA,
    /** A waitlist entry now has a berth that fits it. */
    WAITLIST_MATCH_FOUND,
    /** A waitlist entry was turned into a real, confirmed reservation. */
    WAITLIST_FULFILLED
}
