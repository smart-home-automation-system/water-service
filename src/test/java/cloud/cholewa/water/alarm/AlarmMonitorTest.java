package cloud.cholewa.water.alarm;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.repository.WaterAlertRepository;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The monitor of the alarms (HAS-319): the open episodes read before anything is decided, a failing publish swallowed and
//tried again, the rows stored, every pass bounded
class AlarmMonitorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //10:00 in the house
    private static final Instant START = Instant.parse("2026-11-05T09:00:00Z");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final AlarmProperties PROPERTIES = new AlarmProperties(100.0, Duration.ofMinutes(5),
        Duration.ofMinutes(5), 3.0, Duration.ofMinutes(5), Duration.ofHours(1));

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final ValveRegistry registry = mock(ValveRegistry.class);
    private final ValveTracker tracker = new ValveTracker(clock);
    private final WaterAlertRepository repository = mock(WaterAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private AlarmMonitor sut;

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(new WaterValve(139L, "neowatervalve-a1b2c3d4e5f6", "192.0.2.15")));
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.<WaterAlertEntity>getArgument(0).withId(5L)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        sut = new AlarmMonitor(registry, tracker, repository, publisher, PROPERTIES,
            new ValveProperties(Duration.ofMinutes(5)), clock);
    }

    //no mains water for five minutes: one alert, stored as told
    @Test
    void should_alert_publish_and_store() {
        passesWithPressure(40.0, 0, 5);

        verify(publisher).publishAlert(
            "No mains water: the pressure at the water valve is 0.4 bar since 10:00 (5 min), below 1 bar.");
        assertThat(sut.current().open()).containsEntry(Alarm.NO_MAINS, new WaterAlertEntity(5L, "no mains", T0,
            T0.plusMinutes(5), null));
        assertThat(sut.current().unsaved()).isEmpty();
    }

    //a publish that fails neither fails the pass nor counts as told: the alert again with the next pass
    @Test
    void should_swallow_a_failing_publish_and_try_it_again() {
        when(publisher.publishAlert(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker down")))
            .thenReturn(Mono.empty());

        passesWithPressure(40.0, 0, 5);
        assertThat(sut.current().open().get(Alarm.NO_MAINS).lastToldAt()).isNull();

        passesWithPressure(40.0, 6, 6);
        verify(publisher, times(2)).publishAlert(anyString());
        assertThat(sut.current().open().get(Alarm.NO_MAINS).lastToldAt()).isEqualTo(T0.plusMinutes(6));
    }

    //a publish that never answers is cut off, the pass completes within its bound
    @Test
    void should_cut_off_a_publish_that_never_answers() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.never());
        passesWithPressure(40.0, 0, 4);
        at(5);
        tracker.report(201, NumberStatus.builder().value(40.0).build());

        StepVerifier.withVirtualTime(sut::check)
            .expectSubscription()
            .thenAwait(AlarmMonitor.NOTICE_TIMEOUT)
            .verifyComplete();
        assertThat(sut.current().open().get(Alarm.NO_MAINS).lastToldAt()).isNull();
    }

    //after a restart an open, told alarm is taken up - not alerted again
    @Test
    void should_take_up_the_open_alarms_after_a_restart() {
        when(repository.findByClosedAtIsNull())
            .thenReturn(Flux.just(new WaterAlertEntity(3L, "no mains", T0, T0.plusMinutes(5), null)));

        passesWithPressure(40.0, 10, 12);

        verify(publisher, never()).publishAlert(anyString());
        assertThat(sut.current().open().get(Alarm.NO_MAINS).id()).isEqualTo(3L);
    }

    //nothing is decided before the open episodes were read
    @Test
    void should_decide_nothing_before_the_open_alarms_are_read() {
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.error(new IllegalStateException("database down")));

        passesWithPressure(40.0, 0, 10);

        verify(publisher, never()).publishAlert(anyString());
        assertThat(sut.current().loaded()).isFalse();
    }

    @Test
    void should_judge_nothing_while_no_valve_is_registered() {
        when(registry.current()).thenReturn(Optional.empty());

        at(30);
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher, never()).publishAlert(anyString());
    }

    //a row the database did not take is stored with the next pass
    @Test
    void should_store_a_row_again_after_a_failed_write() {
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> Mono.just(call.<WaterAlertEntity>getArgument(0).withId(7L)))
            .when(repository).save(any());

        passesWithPressure(40.0, 0, 5);
        assertThat(sut.current().unsaved()).hasSize(1);

        passesWithPressure(40.0, 6, 6);
        assertThat(sut.current().unsaved()).isEmpty();
        assertThat(sut.current().open().get(Alarm.NO_MAINS).id()).isEqualTo(7L);
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.never());

        StepVerifier.withVirtualTime(sut::check)
            .expectSubscription()
            .expectNoEvent(AlarmMonitor.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //a pass a minute from 10:00 + first to 10:00 + last, the valve reporting the pressure before each
    private void passesWithPressure(final double kilopascals, final int first, final int last) {
        for (int minute = first; minute <= last; minute++) {
            at(minute);
            tracker.report(201, NumberStatus.builder().value(kilopascals).build());
            sut.check().as(StepVerifier::create).verifyComplete();
        }
    }

    private void at(final int minute) {
        now.set(START.plus(Duration.ofMinutes(minute)));
    }
}
