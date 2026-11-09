package cloud.cholewa.water.alarm;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.database.repository.WaterAlertRepository;
import cloud.cholewa.water.softener.SoftenerProperties;
import cloud.cholewa.water.softener.SoftenerReading;
import cloud.cholewa.water.softener.SoftenerSamples;
import cloud.cholewa.water.softener.SoftenerTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The monitor of the softener (HAS-399): the open episodes read before anything is decided - a restart repeats nothing -
//each alarm published at its level, a failing publish swallowed and tried again, the rows stored
class SoftenerAlarmMonitorTest {

    //10:00 in the house
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final AlarmProperties ALARM = new AlarmProperties(100.0, Duration.ofMinutes(5),
        Duration.ofMinutes(5), 3.0, Duration.ofMinutes(5), Duration.ofHours(1));
    private static final SoftenerProperties SOFTENER = SoftenerSamples.properties(URI.create("http://localhost:1"));

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final SoftenerTracker tracker = mock(SoftenerTracker.class);
    private final WaterAlertRepository repository = mock(WaterAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private SoftenerAlarmMonitor sut;

    @BeforeEach
    void setUp() {
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.<WaterAlertEntity>getArgument(0).withId(7L)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        reads(new SoftenerReading.Alerts(false, false, false, false, false, false));
        sut = new SoftenerAlarmMonitor(tracker, repository, publisher, SOFTENER, ALARM, clock);
    }

    private void reads(final SoftenerReading.Alerts alerts) {
        final SoftenerReading reading = new SoftenerReading(true, 102.0, 503.0, 2218.0, 425408.0, 0.0, 10.0, 5, 0, 156,
            12.0, 917, -70, alerts);
        when(tracker.current()).thenReturn(new SoftenerTracker.State(reading, LocalDateTime.now(clock), clock.instant(),
            true, 0));
    }

    private void pass() {
        sut.check().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_alert_a_leak_at_the_level_error_and_store_its_episode() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));

        pass();

        verify(publisher).publishAlert("The water softener reports a leak (since 10:00).");
        verify(repository).save(new WaterAlertEntity(null, "softener leak", T0, T0, null));
        assertThat(sut.current().open().get(SoftenerAlarm.LEAK).id()).isEqualTo(7L);
        assertThat(sut.current().unsaved()).isEmpty();
    }

    //the salt lasts days: published at warn
    @Test
    void should_alert_the_salt_at_the_level_warn() {
        reads(new SoftenerReading.Alerts(true, false, false, false, false, false));

        pass();

        verify(publisher).publishReminder("Low salt in the water softener: 10 % left, about 5 days to run out (since 10:00).");
        verify(publisher, never()).publishAlert(anyString());
    }

    //the broker down: the pass completes, the alert is published with the next one and only then stored as told
    @Test
    void should_swallow_a_failing_publish_and_try_again_with_the_next_pass() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        when(publisher.publishAlert(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker down")))
            .thenReturn(Mono.empty());

        pass();
        assertThat(sut.current().open().get(SoftenerAlarm.LEAK).lastToldAt()).isNull();

        clock.moveBy(Duration.ofMinutes(1));
        pass();

        verify(publisher, times(2)).publishAlert(anyString());
        assertThat(sut.current().open().get(SoftenerAlarm.LEAK).lastToldAt()).isEqualTo(T0.plusMinutes(1));
    }

    //a publish that never answers is cut off - the pass is not stalled
    @Test
    void should_cut_off_a_publish_that_hangs() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.check())
            .thenAwait(SoftenerAlarmMonitor.NOTICE_TIMEOUT)
            .verifyComplete();

        assertThat(sut.current().open().get(SoftenerAlarm.LEAK).lastToldAt()).isNull();
    }

    //a restart takes up the episode it finds - the alert is not repeated; the episodes of the valve are not its own
    @Test
    void should_take_up_the_open_episodes_after_a_restart() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.just(
            new WaterAlertEntity(3L, "softener leak", T0.minusHours(2), T0.minusMinutes(30), null),
            new WaterAlertEntity(4L, "frost", T0.minusHours(2), T0.minusMinutes(30), null)));

        pass();

        verify(publisher, never()).publishAlert(anyString());
        verify(publisher, never()).publishReminder(anyString());
        assertThat(sut.current().open()).containsOnlyKeys(SoftenerAlarm.LEAK);
    }

    //nothing is decided before the open episodes were read
    @Test
    void should_decide_nothing_before_the_open_episodes_were_read() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        when(repository.findByClosedAtIsNull())
            .thenReturn(Flux.error(new IllegalStateException("database down")))
            .thenReturn(Flux.empty());

        pass();
        verify(publisher, never()).publishAlert(anyString());

        pass();
        verify(publisher).publishAlert(anyString());
    }

    //a row the database does not take is tried again with the next pass
    @Test
    void should_store_a_row_again_when_the_database_refused_it() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        doReturn(Mono.error(new IllegalStateException("database down")))
            .doAnswer(call -> Mono.just(call.<WaterAlertEntity>getArgument(0).withId(9L)))
            .when(repository).save(any());

        pass();
        assertThat(sut.current().unsaved()).hasSize(1);

        clock.moveBy(Duration.ofMinutes(1));
        pass();
        assertThat(sut.current().unsaved()).isEmpty();
        assertThat(sut.current().open().get(SoftenerAlarm.LEAK).id()).isEqualTo(9L);
    }

    //the info of the return at the level info
    @Test
    void should_tell_the_return() {
        reads(new SoftenerReading.Alerts(false, true, false, false, false, false));
        pass();
        clock.moveBy(Duration.ofMinutes(10));
        reads(new SoftenerReading.Alerts(false, false, false, false, false, false));

        pass();

        verify(publisher).publishInfo("Over: the water softener reports a leak no more - from 10:00 to 10:10 (10 min).");
        assertThat(sut.current().open()).isEmpty();
    }

    //below the minute of the pass, and room for more than one notice cut off
    @Test
    void should_be_bounded_below_its_minute() {
        assertThat(SoftenerAlarmMonitor.LIMIT).isLessThan(Duration.ofMinutes(1))
            .isGreaterThan(SoftenerAlarmMonitor.NOTICE_TIMEOUT.multipliedBy(2));
    }
}
