package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AvailabilityService {

    public static class BerthSuggestion {
        public Berth berth;
        public boolean lengthCompatible;
        public boolean hasConfirmedConflict;
        public String note;
    }

    private final JsonCollectionStore<Berth> berthStore;
    private final JsonCollectionStore<Reservation> reservationStore;

    public AvailabilityService(JsonCollectionStore<Berth> berthStore, JsonCollectionStore<Reservation> reservationStore) {
        this.berthStore = berthStore;
        this.reservationStore = reservationStore;
    }

    /** Suggests berths that are both open (not closed) and physically compatible with the given
     *  LOA for the requested date range, ranked with confirmed-conflict-free options first. */
    public List<BerthSuggestion> suggest(LocalDate start, LocalDate end, Double loaFt) {
        List<Reservation> all = reservationStore.findAll();

        return berthStore.findAll().stream()
                .map(berth -> {
                    BerthSuggestion s = new BerthSuggestion();
                    s.berth = berth;

                    if (loaFt == null || berth.lengthFt == null) {
                        s.lengthCompatible = true; // unknown either way — don't rule it out, just don't claim certainty
                        if (berth.lengthFt == null) {
                            s.note = "Berth length not on record — verify manually before confirming.";
                        }
                    } else {
                        s.lengthCompatible = berth.lengthFt >= loaFt;
                        if (!s.lengthCompatible) {
                            s.note = String.format("Berth is %.0f' — shorter than the %.0f' LOA.", berth.lengthFt, loaFt);
                        }
                    }

                    boolean conflict = all.stream().anyMatch(r ->
                            berth.id.equals(r.berthId)
                                    && r.status == ReservationStatus.CONFIRMED
                                    && overlaps(r, start, end));
                    s.hasConfirmedConflict = conflict;

                    if (berth.status != BerthStatus.ACTIVE) {
                        s.note = (s.note == null ? "" : s.note + " ") + "Berth is currently " + berth.status + ".";
                    }
                    return s;
                })
                .sorted((a, b) -> {
                    int score = score(a) - score(b);
                    if (score != 0) return score;
                    Double la = a.berth.lengthFt == null ? Double.MAX_VALUE : a.berth.lengthFt;
                    Double lb = b.berth.lengthFt == null ? Double.MAX_VALUE : b.berth.lengthFt;
                    return la.compareTo(lb);
                })
                .toList();
    }

    private int score(BerthSuggestion s) {
        int score = 0;
        if (!s.lengthCompatible) score += 10;
        if (s.hasConfirmedConflict) score += 5;
        if (s.berth.status != BerthStatus.ACTIVE) score += 20;
        return score;
    }

    private boolean overlaps(Reservation r, LocalDate start, LocalDate end) {
        if (r.startDate == null || r.endDate == null) return false;
        return !r.startDate.isAfter(end) && !start.isAfter(r.endDate);
    }
}
