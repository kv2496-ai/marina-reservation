package edu.whoi.marina.service;

import edu.whoi.marina.domain.RecurrenceFrequency;
import edu.whoi.marina.domain.RecurrenceRule;
import edu.whoi.marina.domain.Reservation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceServiceTest {

    private final RecurrenceService service = new RecurrenceService();

    @Test
    void weeklyRecurrence_expandsCorrectDatesAndPreservesSpan() {
        Reservation template = new Reservation();
        template.startDate = LocalDate.of(2030, 1, 1); // a Tuesday
        template.endDate = LocalDate.of(2030, 1, 2);
        template.recurring = true;
        template.recurrenceRule = new RecurrenceRule();
        template.recurrenceRule.frequency = RecurrenceFrequency.WEEKLY;
        template.recurrenceRule.interval = 1;
        template.recurrenceRule.count = 4;

        List<Reservation> occurrences = service.expand(template);

        assertThat(occurrences).hasSize(4);
        assertThat(occurrences.get(0).startDate).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(occurrences.get(1).startDate).isEqualTo(LocalDate.of(2030, 1, 8));
        assertThat(occurrences.get(2).startDate).isEqualTo(LocalDate.of(2030, 1, 15));
        assertThat(occurrences.get(3).startDate).isEqualTo(LocalDate.of(2030, 1, 22));
        // one-day span preserved on every occurrence
        assertThat(occurrences).allSatisfy(o -> assertThat(o.endDate).isEqualTo(o.startDate.plusDays(1)));
        // every occurrence gets its own id but shares one group id
        assertThat(occurrences.stream().map(o -> o.id).distinct().count()).isEqualTo(4);
        assertThat(occurrences.stream().map(o -> o.recurrenceGroupId).distinct()).hasSize(1);
    }

    @Test
    void recurrenceWithUntilDate_stopsAtBoundary() {
        Reservation template = new Reservation();
        template.startDate = LocalDate.of(2030, 3, 1);
        template.endDate = LocalDate.of(2030, 3, 1);
        template.recurring = true;
        template.recurrenceRule = new RecurrenceRule();
        template.recurrenceRule.frequency = RecurrenceFrequency.DAILY;
        template.recurrenceRule.interval = 1;
        template.recurrenceRule.count = 1000; // way more than the until date allows
        template.recurrenceRule.until = "2030-03-05";

        List<Reservation> occurrences = service.expand(template);

        assertThat(occurrences).extracting(o -> o.startDate)
                .containsExactly(
                        LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 2), LocalDate.of(2030, 3, 3),
                        LocalDate.of(2030, 3, 4), LocalDate.of(2030, 3, 5));
    }

    @Test
    void missingRecurrenceRule_throws() {
        Reservation template = new Reservation();
        template.startDate = LocalDate.of(2030, 1, 1);
        template.endDate = LocalDate.of(2030, 1, 1);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.expand(template));
    }
}
