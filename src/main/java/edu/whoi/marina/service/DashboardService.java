package edu.whoi.marina.service;

import edu.whoi.marina.domain.*;
import edu.whoi.marina.store.JsonCollectionStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    public static class DashboardSummary {
        public LocalDate asOf;
        public List<Reservation> arrivalsNext7Days;
        public List<Reservation> departuresNext7Days;
        public List<Reservation> occupiedNow;
        public List<Berth> freeBerthsNow;
        public List<ValidationFlag> unresolvedHardConflicts;
        public List<ValidationFlag> unresolvedWarnings;
        public Map<String, Long> reservationCountByKind;
    }

    private final JsonCollectionStore<Reservation> reservationStore;
    private final JsonCollectionStore<Berth> berthStore;
    private final JsonCollectionStore<ValidationFlag> flagStore;

    public DashboardService(JsonCollectionStore<Reservation> reservationStore,
                             JsonCollectionStore<Berth> berthStore,
                             JsonCollectionStore<ValidationFlag> flagStore) {
        this.reservationStore = reservationStore;
        this.berthStore = berthStore;
        this.flagStore = flagStore;
    }

    public DashboardSummary summary(boolean hideHistorical) {
        LocalDate today = LocalDate.now();
        LocalDate weekOut = today.plusDays(7);

        List<Reservation> allIncludingCanceled = reservationStore.findAll();
        java.util.Map<String, Reservation> reservationById = allIncludingCanceled.stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.id, r -> r, (x, y) -> x));

        List<Reservation> all = allIncludingCanceled.stream()
                .filter(r -> r.status != ReservationStatus.CANCELED)
                .toList();

        DashboardSummary s = new DashboardSummary();
        s.asOf = today;

        s.arrivalsNext7Days = all.stream()
                .filter(r -> r.startDate != null && !r.startDate.isBefore(today) && !r.startDate.isAfter(weekOut))
                .sorted((a, b) -> a.startDate.compareTo(b.startDate))
                .toList();

        s.departuresNext7Days = all.stream()
                .filter(r -> r.endDate != null && !r.endDate.isBefore(today) && !r.endDate.isAfter(weekOut))
                .sorted((a, b) -> a.endDate.compareTo(b.endDate))
                .toList();

        s.occupiedNow = all.stream()
                .filter(r -> r.startDate != null && r.endDate != null
                        && !r.startDate.isAfter(today) && !r.endDate.isBefore(today))
                .toList();

        java.util.Set<String> occupiedBerthIds = s.occupiedNow.stream()
                .map(r -> r.berthId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        s.freeBerthsNow = berthStore.findAll().stream()
                .filter(b -> b.status == BerthStatus.ACTIVE)
                .filter(b -> !occupiedBerthIds.contains(b.id))
                .toList();

        List<ValidationFlag> unresolved = flagStore.findAll().stream()
                .filter(f -> !f.resolved)
                .filter(f -> !hideHistorical || !isHistorical(f, reservationById, today))
                .toList();
        s.unresolvedHardConflicts = unresolved.stream().filter(f -> f.severity == ValidationSeverity.HARD_CONFLICT).toList();
        s.unresolvedWarnings = unresolved.stream().filter(f -> f.severity != ValidationSeverity.HARD_CONFLICT).toList();

        s.reservationCountByKind = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> r.kind.toString(), java.util.stream.Collectors.counting()));

        return s;
    }

    /** A flag counts as historical (and gets hidden when the caller asks) if the reservation it's
     *  attached to already ended before today — matches the "past reservations aren't actionable"
     *  rule used by the Resolve tab. Flags on a reservation that no longer exists are never hidden,
     *  since that's a data-quality issue in its own right, not a stale historical one. */
    private boolean isHistorical(ValidationFlag flag, java.util.Map<String, Reservation> reservationById, LocalDate today) {
        Reservation r = reservationById.get(flag.reservationId);
        return r != null && r.endDate != null && r.endDate.isBefore(today);
    }
}
