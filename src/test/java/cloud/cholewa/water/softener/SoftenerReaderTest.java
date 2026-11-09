package cloud.cholewa.water.softener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.water.database.repository.SoftenerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The read of the softener every five minutes (HAS-397): the devices listed at the first pass and once a day, the detail
//every pass; a failed read a missed pass, two in a row unreachable, no softener on the account unreachable at once; one
//WARN an hour per kind of failure
class SoftenerReaderTest {

    private static final URI CLOUD = URI.create("http://localhost:1/v1");
    private static final IquaDevice LISTED = new IquaDevice("dev-1", "SN1", "Softener", "demand_softener", "thing-1",
        true, null, null);
    private static final IquaDevice DETAIL = new IquaDevice("dev-1", "SN1", "Softener", "demand_softener", "thing-1",
        true, Map.of("total_regens", new IquaProperty("total_regens", SoftenerSamples.JSON.getNodeFactory()
            .numberNode(156), null, null, null)), null);

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final IquaClient client = mock(IquaClient.class);
    private final SoftenerTracker tracker = new SoftenerTracker();
    private final SoftenerRepository repository = mock(SoftenerRepository.class);
    private final ListAppender<ILoggingEvent> readerLog = new ListAppender<>();
    private SoftenerReader reader;

    @BeforeEach
    void setUp() {
        when(repository.recordReading(any(), any())).thenReturn(Mono.just(true));
        reader = new SoftenerReader(client, tracker, repository, SoftenerSamples.properties(CLOUD), clock);
        readerLog.start();
        ((Logger) LoggerFactory.getLogger(SoftenerReader.class)).addAppender(readerLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SoftenerReader.class)).detachAppender(readerLog);
    }

    private List<ILoggingEvent> warnings() {
        return readerLog.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    void should_list_the_devices_and_read_the_softener() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        final SoftenerTracker.State state = tracker.current();
        assertThat(state.reading().totalRegenerations()).isEqualTo(156);
        assertThat(state.measuredAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(state.reachable()).isTrue();
    }

    //the scheduled Mono is subscribed to again for every run: the time and the list are read inside it
    @Test
    void should_list_the_devices_once_a_day_and_read_every_pass() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();

        verify(client, times(1)).devices();
        verify(client, times(2)).detail("dev-1");
        assertThat(tracker.current().measuredAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 5));

        clock.moveBy(Duration.ofDays(1));
        pass.as(StepVerifier::create).verifyComplete();
        verify(client, times(2)).devices();
    }

    @Test
    void should_pick_the_softener_of_the_serial_number() {
        reader = new SoftenerReader(client, tracker, repository, SoftenerSamples.properties(CLOUD, "SN2"), clock);
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED,
            new IquaDevice("dev-2", "SN2", "Second", "demand_softener", "thing-2", true, null, null))));
        when(client.detail("dev-2")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).detail("dev-1");
        assertThat(tracker.current().reading()).isNotNull();
    }

    @Test
    void should_be_unreachable_at_once_without_a_softener_and_warn_once_an_hour() {
        when(client.devices()).thenReturn(Mono.just(List.of()));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isNull();
        assertThat(warnings()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
            .isEqualTo("The iQua cloud could not be read: The iQua account has no softener"));
        //listed anew every pass while none is found
        verify(client, times(2)).devices();
    }

    @Test
    void should_keep_the_reading_and_be_unreachable_after_two_missed_passes() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL),
            Mono.error(new IquaException("The iQua cloud answered HTTP 503 for the softener")));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();
        assertThat(tracker.current().reachable()).isTrue();
        clock.moveBy(Duration.ofMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isFalse();
        assertThat(tracker.current().reading()).isNotNull();
        assertThat(tracker.current().measuredAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void should_say_once_when_the_cloud_is_read_again() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 503 for x")),
            Mono.just(DETAIL));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        assertThat(readerLog.list).filteredOn(event -> event.getLevel() == Level.INFO)
            .extracting(ILoggingEvent::getFormattedMessage)
            .contains("The iQua cloud is read again");
        assertThat(tracker.current().reachable()).isTrue();
    }

    @Test
    void should_log_a_property_it_does_not_read_once_at_debug() {
        ((Logger) LoggerFactory.getLogger(SoftenerReader.class)).setLevel(Level.DEBUG);
        try {
            when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
            when(client.detail("dev-1")).thenReturn(Mono.just(new IquaDevice("dev-1", "SN1", null, null, null, true,
                Map.of("brine_tank_volume", new IquaProperty("brine_tank_volume", null, null, null, null)), null)));
            final Mono<Void> pass = reader.read();

            pass.as(StepVerifier::create).verifyComplete();
            pass.as(StepVerifier::create).verifyComplete();

            assertThat(readerLog.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("The softener reports a property the service does not read: brine_tank_volume");
        } finally {
            ((Logger) LoggerFactory.getLogger(SoftenerReader.class)).setLevel(null);
        }
    }

    //a softener re-registered in the iQua application has a new id: the old one answers 404 and the devices are listed anew
    @Test
    void should_list_the_devices_anew_after_a_404_of_the_softener() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 404 for x", 404)),
            Mono.just(DETAIL));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(client, times(2)).devices();
        assertThat(tracker.current().reading()).isNotNull();
    }

    @Test
    void should_keep_the_softener_after_a_failure_of_the_cloud() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 503 for x", 503)),
            Mono.just(DETAIL));
        final Mono<Void> pass = reader.read();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(client, times(1)).devices();
    }

    //an empty variable of the manifest is no serial number: the first softener of the account
    @Test
    void should_take_a_blank_serial_number_as_none() {
        reader = new SoftenerReader(client, tracker, repository, SoftenerSamples.properties(CLOUD, " "), clock);
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isTrue();
    }

    //HAS-398: every reading stored - the repository decides whether it is a new row
    @Test
    void should_store_every_reading() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        verify(repository).recordReading(SoftenerReading.of(DETAIL), LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(reader.deviceId()).contains("dev-1");
    }

    //a failed write is a WARN, never a missed read
    @Test
    void should_keep_the_reading_when_it_cannot_be_stored() {
        when(repository.recordReading(any(), any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.current().reachable()).isTrue();
        assertThat(tracker.current().missed()).isZero();
        assertThat(warnings()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
            .startsWith("The reading of the softener could not be stored"));
    }

    //the reading arrived and only its write hangs: the bound cuts the pass, the read is not missed
    @Test
    void should_not_count_a_pass_cut_while_storing_as_missed() {
        when(repository.recordReading(any(), any())).thenReturn(Mono.never());
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        StepVerifier.withVirtualTime(() -> reader.read())
            .thenAwait(SoftenerReader.LIMIT)
            .verifyComplete();

        assertThat(tracker.current().missed()).isZero();
        assertThat(tracker.current().reading()).isNotNull();
    }

    //a read that hangs is cut off by the bound of the pass and counts as missed
    @Test
    void should_count_a_pass_cut_off_by_its_bound_as_missed() {
        final PublisherProbe<IquaDevice> hanging = PublisherProbe.of(Mono.never());
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(hanging.mono());

        StepVerifier.withVirtualTime(() -> reader.read())
            .thenAwait(SoftenerReader.LIMIT)
            .verifyComplete();

        hanging.assertWasCancelled();
        assertThat(tracker.current().reachable()).isTrue();
        assertThat(tracker.current().missed()).isEqualTo(1);
    }

    //HAS-399: the regeneration from the answer of the real softener - regen_status_enum 0 since the end of the regeneration
    //of 2026-11-09 (its updated_at 03:03:47Z), the regeneration at 02:00 (regen_time_secs 7200)
    @Test
    void should_read_the_regeneration_from_the_real_answer() {
        final IquaDevice real = SoftenerSamples.JSON.convertValue(
            SoftenerSamples.JSON.readTree(SoftenerSamples.detail()).path("device"), IquaDevice.class);
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(real));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.regeneration()).isEqualTo(new Regeneration(null, null, LocalTime.of(2, 0),
            LocalDateTime.of(2026, 11, 9, 10, 0)));
    }

    //a status other than 0 is a regeneration, from the time the cloud changed it, in the time of the house
    @Test
    void should_see_a_regeneration_from_the_time_its_status_changed() {
        final IquaDevice regenerating = new IquaDevice("dev-1", "SN1", "Softener", "demand_softener", "thing-1", true,
            Map.of("regen_status_enum", new IquaProperty("regen_status_enum", SoftenerSamples.JSON.getNodeFactory()
                    .numberNode(2), null, null, "2026-11-09T08:55:10Z"),
                "regen_time_secs", new IquaProperty("regen_time_secs", SoftenerSamples.JSON.getNodeFactory()
                    .numberNode(7200), null, null, "2026-10-28T07:59:21Z")), null);
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(regenerating));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.regeneration().regenerating()).isTrue();
        assertThat(tracker.regeneration().startedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 9, 55, 10));
    }

    //a status not 0 on a day without a regeneration (days_since_last_regen 3) is something else - queued for the night, say
    @Test
    void should_not_take_a_status_for_a_regeneration_on_a_day_without_one() {
        final IquaDevice queued = new IquaDevice("dev-1", "SN1", "Softener", "demand_softener", "thing-1", true,
            Map.of("regen_status_enum", new IquaProperty("regen_status_enum", SoftenerSamples.JSON.getNodeFactory()
                    .numberNode(1), null, null, "2026-11-09T08:55:10Z"),
                "days_since_last_regen", new IquaProperty("days_since_last_regen", SoftenerSamples.JSON.getNodeFactory()
                    .numberNode(3), null, null, "2026-11-09T00:00:00Z")), null);
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(queued));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.regeneration().regenerating()).isFalse();
        assertThat(tracker.regeneration().checkedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
    }

    //a reading without the status tells nothing of the regeneration
    @Test
    void should_tell_nothing_of_the_regeneration_without_its_status() {
        when(client.devices()).thenReturn(Mono.just(List.of(LISTED)));
        when(client.detail("dev-1")).thenReturn(Mono.just(DETAIL));

        reader.read().as(StepVerifier::create).verifyComplete();

        assertThat(tracker.regeneration()).isEqualTo(Regeneration.NONE);
    }
}
