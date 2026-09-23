package edu.whoi.marina.service;

import edu.whoi.marina.domain.RecurrenceFrequency;
import edu.whoi.marina.domain.RecurrenceRule;
import edu.whoi.marina.domain.Reservation;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RecurrenceService {

    private static final int MAX_OCCURRENCES = 260; // ~5 years weekly — sane upper bound for a prototype

    /** Expands a template reservation (with recurring=true and a recurrenceRule set) into a list
     *  of independent Reservation instances sharing a recurrenceGroupId, each with its own id so
     *  they can be individually edited/canceled later without touching the rest of the series. */
    public List<Reservation> expand(Reservation template) {
        RecurrenceRule rule = template.recurrenceRule;
        if (rule == null || rule.frequency == null) {
            throw new IllegalArgumentException("recurrenceRule with a frequency is required for a recurring reservation");
        }
        int interval = Math.max(1, rule.interval);
        long spanDays = ChronoUnit.DAYS.between(template.startDate, template.endDate);
        LocalDate until = rule.until != null ? LocalDate.parse(rule.until) : null;
        int maxCount = rule.count != null ? Math.min(rule.count, MAX_OCCURRENCES) : MAX_OCCURRENCES;

        String groupId = UUID.randomUUID().toString();
        List<Reservation> occurrences = new ArrayList<>();
        LocalDate cursorStart = template.startDate;

        for (int i = 0; i < maxCount; i++) {
            if (until != null && cursorStart.isAfter(until)) {
                break;
            }
            Reservation occ = copyOf(template);
            occ.id = UUID.randomUUID().toString();
            occ.recurrenceGroupId = groupId;
            occ.startDate = cursorStart;
            occ.endDate = cursorStart.plusDays(spanDays);
            occurrences.add(occ);

            cursorStart = switch (rule.frequency) {
                case DAILY -> cursorStart.plusDays((long) interval);
                case WEEKLY -> cursorStart.plusWeeks(interval);
                case MONTHLY -> cursorStart.plusMonths(interval);
            };
        }
        return occurrences;
    }

    private Reservation copyOf(Reservation src) {
        Reservation r = new Reservation();
        r.kind = src.kind;
        r.title = src.title;
        r.vesselId = src.vesselId;
        r.vesselNameSnapshot = src.vesselNameSnapshot;
        r.berthId = src.berthId;
        r.berthNameSnapshot = src.berthNameSnapshot;
        r.startTime = src.startTime;
        r.endTime = src.endTime;
        r.status = src.status;
        r.recurring = true;
        r.recurrenceRule = src.recurrenceRule;
        r.raftingApproved = src.raftingApproved;
        r.source = src.source;
        r.category = src.category;
        r.notes = src.notes;
        r.createdBy = src.createdBy;
        r.updatedBy = src.updatedBy;
        return r;
    }

    static {
        // Guard against RecurrenceFrequency gaining a value without a matching branch above.
        assert RecurrenceFrequency.values().length == 3;
    }
}
