package edu.whoi.marina.service;

import java.util.List;

/** Summary of what an automated resolve action actually did, for the UI to show staff. */
public class ResolveResult {
    public String outcome; // short machine-ish tag, e.g. "SWITCH_OFFERED", "WAITLISTED", "BOTH_CANCELED"
    public String message; // human-readable summary
    public List<String> alternativeBerthNames;

    public ResolveResult(String outcome, String message) {
        this.outcome = outcome;
        this.message = message;
    }

    public ResolveResult withAlternatives(List<String> names) {
        this.alternativeBerthNames = names;
        return this;
    }
}
