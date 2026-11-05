package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ObjectStatus;
import cloud.cholewa.shelly.model.SwitchStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//Which component of the valve is which value (HAS-316): the keys of the Neo Smart Water Valve
class ValveTrackerTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant NOW = Instant.parse("2026-11-04T18:51:20.123456789Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 4, 19, 51, 20, 123456000);

    private final ValveTracker sut = new ValveTracker(Clock.fixed(NOW, HOUSE));

    @Test
    void should_know_nothing_before_a_report() {
        assertThat(sut.current()).isEqualTo(ValveState.UNKNOWN);
    }

    @Test
    void should_take_each_component_as_its_value_with_the_time_of_the_report() {
        sut.report(200, BooleanStatus.builder().value(true).lastUpdateTs(1L).build());
        sut.report(200, NumberStatus.builder().value(0.012).lastUpdateTs(1L).build());
        sut.report(201, NumberStatus.builder().value(495.04377).lastUpdateTs(1L).build());
        sut.report(202, NumberStatus.builder().value(12.9).lastUpdateTs(1L).build());
        sut.report(200, ObjectStatus.builder()
            .value(Map.of("counter", Map.of("total", 61.665843064, "minute_ts", 1L, "by_minute", List.of(0, 0, 0))))
            .lastUpdateTs(1L).build());

        assertThat(sut.current()).isEqualTo(new ValveState(new Reported<>(true, AT), new Reported<>(0.012, AT),
            new Reported<>(495.04377, AT), new Reported<>(12.9, AT), new Reported<>(61.665843064, AT),
            new Reported<>(0.0, AT), null));
    }

    //a whole number of the counter arrives as an Integer
    @Test
    void should_read_a_whole_total_of_the_counter() {
        sut.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 62))).build());

        assertThat(sut.current().counterTotal()).isEqualTo(new Reported<>(62.0, AT));
    }

    //reported without a value: the component is there, its value not known
    @Test
    void should_keep_a_report_without_a_value_as_not_known() {
        sut.report(201, NumberStatus.builder().value(495.0).build());
        sut.report(201, NumberStatus.builder().build());
        sut.report(200, ObjectStatus.builder().value(Map.of("counter", "none")).build());

        assertThat(sut.current().pressure()).isEqualTo(new Reported<>(null, AT));
        assertThat(sut.current().counterTotal()).isEqualTo(new Reported<>(null, AT));
    }

    @Test
    void should_leave_what_the_house_does_not_read() {
        sut.report(203, NumberStatus.builder().value(1.0).build());
        sut.report(201, BooleanStatus.builder().value(true).build());
        sut.report(0, SwitchStatus.builder().output(true).build());

        assertThat(sut.current()).isEqualTo(ValveState.UNKNOWN);
    }

    @Test
    void should_read_the_five_components_and_no_other() {
        assertThat(ValveTracker.isRead("boolean", 200)).isTrue();
        assertThat(ValveTracker.isRead("number", 200)).isTrue();
        assertThat(ValveTracker.isRead("number", 202)).isTrue();
        assertThat(ValveTracker.isRead("object", 200)).isTrue();
        assertThat(ValveTracker.isRead("number", 203)).isFalse();
        assertThat(ValveTracker.isRead("boolean", 201)).isFalse();
        assertThat(ValveTracker.isRead("service", 0)).isFalse();
        assertThat(ValveTracker.isRead("number", null)).isFalse();
        assertThat(ValveTracker.isRead(null, 200)).isFalse();
    }

    @Test
    void should_keep_the_last_word_of_the_valve_on_the_broker_and_forget_on_a_reset() {
        sut.report(202, NumberStatus.builder().value(12.9).build());
        sut.online(true);

        assertThat(sut.current().online()).isTrue();
        assertThat(sut.current().temperature()).isNotNull();

        sut.reset();

        assertThat(sut.current()).isEqualTo(ValveState.UNKNOWN);
    }

    //the last will forgets the values: GET /valve would serve them as fresh for minutes after the valve left
    @Test
    void should_forget_the_values_with_the_last_will() {
        sut.report(202, NumberStatus.builder().value(12.9).build());
        sut.online(true);

        assertThat(sut.current().temperature()).isNotNull();

        sut.online(false);

        assertThat(sut.current()).isEqualTo(ValveState.UNKNOWN.withOnline(false));
    }

    //every flow rate goes to the listener as it arrives, in l/min; the last will and a reset forget the flow (HAS-320)
    @Test
    void should_hand_every_flow_rate_to_the_listener_and_tell_it_to_forget() {
        final List<String> heard = new java.util.ArrayList<>();
        final ValveTracker tracker = new ValveTracker(Clock.fixed(NOW, HOUSE), new ValveListener() {
            @Override
            public void flow(final double litresPerMinute, final LocalDateTime at) {
                heard.add(litresPerMinute + " at " + at);
            }

            @Override
            public void forget() {
                heard.add("forget");
            }
        });

        tracker.report(200, NumberStatus.builder().value(0.0123).build());
        tracker.report(200, NumberStatus.builder().build());
        tracker.report(201, NumberStatus.builder().value(495.0).build());
        tracker.online(false);
        tracker.online(true);
        tracker.reset();

        assertThat(heard).containsExactly("12.3 at " + AT, "forget", "forget");
    }

    //the first of by_minute is the litres of the last whole minute
    @Test
    void should_read_the_litres_of_the_last_minute_of_the_counter() {
        sut.report(200, ObjectStatus.builder()
            .value(Map.of("counter", Map.of("total", 62, "by_minute", List.of(4.5, 2, 0)))).build());

        assertThat(sut.current().lastMinute()).isEqualTo(new Reported<>(4.5, AT));

        sut.report(200, ObjectStatus.builder().value(Map.of("counter", Map.of("total", 62, "by_minute", List.of())))
            .build());

        assertThat(sut.current().lastMinute()).isEqualTo(new Reported<>(null, AT));
    }

    //what is not reported within the staleness is left out, and the newest report is of what is left
    @Test
    void should_leave_out_what_is_stale() {
        final Clock later = Clock.fixed(NOW, HOUSE);
        final ValveTracker tracker = new ValveTracker(later);
        tracker.report(202, NumberStatus.builder().value(12.9).build());
        tracker.report(201, NumberStatus.builder().build());

        final ValveState fresh = tracker.current().freshOnly(AT.plusMinutes(5), Duration.ofMinutes(5));
        final ValveState stale = tracker.current().freshOnly(AT.plusMinutes(5).plusNanos(1000), Duration.ofMinutes(5));

        assertThat(fresh.temperature()).isEqualTo(new Reported<>(12.9, AT));
        assertThat(fresh.pressure()).isNull();
        assertThat(fresh.newestReport()).contains(AT);
        assertThat(stale.temperature()).isNull();
        assertThat(stale.newestReport()).isEmpty();
        assertThat(stale.hasStatus()).isFalse();
    }
}
