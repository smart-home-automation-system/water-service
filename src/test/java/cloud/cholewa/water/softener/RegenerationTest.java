package cloud.cholewa.water.softener;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

//The regeneration of the softener for the flow alarms of the valve (HAS-399): seen from the status of the cloud, the time it
//changed it; a flow that started with it judged from its end; a flow near the time of the regeneration held until the cloud
//said whether it regenerates. The night of 2026-11-09: the regeneration 02:01:51 - 04:03:46, the flow 02:01 - 04:04
class RegenerationTest {

    private static final LocalTime TWO = LocalTime.of(2, 0);
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 11, 9, 0, 0);
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 11, 9, 2, 1, 51);
    private static final LocalDateTime ENDED = LocalDateTime.of(2026, 11, 9, 4, 3, 46);
    private static final LocalDateTime FLOW = LocalDateTime.of(2026, 11, 9, 2, 1);

    //the status read every five minutes: not regenerating until 02:05, then regenerating since 02:01:51
    private static Regeneration seen() {
        return Regeneration.NONE
            .next(false, LocalDateTime.of(2026, 11, 3, 4, 3, 46), TWO, NIGHT.plusHours(2))
            .next(true, STARTED, TWO, LocalDateTime.of(2026, 11, 9, 2, 5));
    }

    @Test
    void should_see_a_regeneration_from_the_time_the_cloud_changed_its_status() {
        final Regeneration regeneration = seen();

        assertThat(regeneration.regenerating()).isTrue();
        assertThat(regeneration.startedAt()).isEqualTo(STARTED);
        assertThat(regeneration.scheduledAt()).isEqualTo(TWO);

        final Regeneration ended = regeneration.next(false, ENDED, TWO, LocalDateTime.of(2026, 11, 9, 4, 5));
        assertThat(ended.regenerating()).isFalse();
        assertThat(ended.startedAt()).isEqualTo(STARTED);
        assertThat(ended.endedAt()).isEqualTo(ENDED);
    }

    //a status without its time of change is dated by the reading; a time after the reading is not believed
    @Test
    void should_date_a_change_by_the_reading_without_its_time() {
        final LocalDateTime read = LocalDateTime.of(2026, 11, 9, 2, 5);

        assertThat(Regeneration.NONE.next(true, null, TWO, read).startedAt()).isEqualTo(read);
        assertThat(Regeneration.NONE.next(true, read.plusMinutes(1), TWO, read).startedAt()).isEqualTo(read);
    }

    //a reading that tells nothing of the status keeps what was seen and when it was told
    @Test
    void should_keep_the_regeneration_through_a_reading_without_a_status() {
        final Regeneration regeneration = seen();

        assertThat(regeneration.next(null, null, null, LocalDateTime.of(2026, 11, 9, 2, 10))).isEqualTo(regeneration);
    }

    //the next regeneration starts a new one
    @Test
    void should_start_a_new_regeneration_after_one_ended() {
        final Regeneration ended = seen().next(false, ENDED, TWO, ENDED.plusMinutes(2));
        final LocalDateTime next = STARTED.plusDays(6);

        final Regeneration again = ended.next(true, next, TWO, next.plusMinutes(3));

        assertThat(again.startedAt()).isEqualTo(next);
        assertThat(again.endedAt()).isNull();
    }

    @Test
    void should_hold_a_flow_while_the_softener_regenerates() {
        assertThat(seen().flowStart(FLOW, LocalDateTime.of(2026, 11, 9, 3, 0)))
            .isEqualTo(new Regeneration.FlowStart(true, FLOW));
    }

    @Test
    void should_judge_a_flow_of_the_regeneration_from_its_end() {
        final Regeneration ended = seen().next(false, ENDED, TWO, LocalDateTime.of(2026, 11, 9, 4, 5));

        final Regeneration.FlowStart start = ended.flowStart(FLOW, LocalDateTime.of(2026, 11, 9, 4, 5));

        assertThat(start).isEqualTo(new Regeneration.FlowStart(false, ENDED));
        assertThat(start.excused(FLOW)).isTrue();
    }

    //a flow started more than LEAD before the regeneration was seen is not its flow, nor one after it ended
    @Test
    void should_not_excuse_a_flow_outside_the_regeneration() {
        final Regeneration ended = seen().next(false, ENDED, TWO, LocalDateTime.of(2026, 11, 9, 4, 5));
        final LocalDateTime before = STARTED.minus(Regeneration.LEAD).minusSeconds(1);
        final LocalDateTime after = ENDED.plusSeconds(1);

        assertThat(ended.flowStart(before, ENDED).excused(before)).isFalse();
        assertThat(ended.flowStart(after, after.plusMinutes(30))).isEqualTo(new Regeneration.FlowStart(false, after));
    }

    //a regeneration whose end the cloud never told excuses its flow for LONGEST at most
    @Test
    void should_excuse_a_flow_for_the_longest_regeneration_at_most() {
        final LocalDateTime longest = STARTED.plus(Regeneration.LONGEST);

        assertThat(seen().flowStart(FLOW, longest)).isEqualTo(new Regeneration.FlowStart(false, longest));
    }

    //02:01 - the cloud has said nothing yet: held until a reading CONFIRM after the start of the flow, HOLD_AT_MOST at most
    @Test
    void should_hold_a_flow_near_the_time_of_the_regeneration_until_the_cloud_tells() {
        final Regeneration quiet = Regeneration.NONE.next(false, null, TWO, NIGHT.plusHours(2));

        assertThat(quiet.flowStart(FLOW, FLOW.plusMinutes(3)).held()).isTrue();
        assertThat(quiet.flowStart(FLOW, FLOW.plus(Regeneration.HOLD_AT_MOST)))
            .isEqualTo(new Regeneration.FlowStart(false, FLOW));

        final Regeneration told = quiet.next(false, null, TWO, FLOW.plus(Regeneration.CONFIRM));
        assertThat(told.flowStart(FLOW, FLOW.plusMinutes(6))).isEqualTo(new Regeneration.FlowStart(false, FLOW));
    }

    //a flow far from the time of the regeneration, and one before the schedule is known, wait for nothing
    @Test
    void should_not_hold_a_flow_far_from_the_time_of_the_regeneration() {
        final Regeneration quiet = Regeneration.NONE.next(false, null, TWO, NIGHT);
        final LocalDateTime noon = NIGHT.plusHours(12);

        assertThat(quiet.flowStart(noon, noon.plusMinutes(1)).held()).isFalse();
        assertThat(quiet.flowStart(NIGHT.plusHours(2).minusMinutes(6), NIGHT.plusHours(2)).held()).isFalse();
        assertThat(quiet.flowStart(NIGHT.plusHours(2).plusMinutes(21), NIGHT.plusHours(2).plusMinutes(22)).held()).isFalse();
        assertThat(Regeneration.NONE.flowStart(FLOW, FLOW.plusMinutes(1)).held()).isFalse();
    }

    //a schedule just after midnight holds a flow just before it, on the day before
    @Test
    void should_hold_a_flow_across_midnight() {
        final Regeneration quiet = Regeneration.NONE.next(false, null, LocalTime.of(0, 2), NIGHT.minusHours(1));
        final LocalDateTime lateEvening = NIGHT.minusMinutes(1);

        assertThat(quiet.flowStart(lateEvening, lateEvening.plusMinutes(2)).held()).isTrue();
    }
}
