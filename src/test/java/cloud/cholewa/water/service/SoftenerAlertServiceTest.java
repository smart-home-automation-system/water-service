package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerAlertReply;
import cloud.cholewa.water.softener.IquaAlert;
import cloud.cholewa.water.softener.IquaClient;
import cloud.cholewa.water.softener.IquaException;
import cloud.cholewa.water.softener.SoftenerReader;
import cloud.cholewa.water.softener.SoftenerSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The alerts of the iQua cloud (HAS-399): kept for one interval of the read - the cloud blocks an account above about 1000
//calls a day - newest first in the time of the house, 503 before the softener is known, 502 when the cloud fails
class SoftenerAlertServiceTest {

    private static final IquaAlert OLDER = new IquaAlert("a0", "service", "Service", "Call the dealer", "info",
        "2026-11-01T07:00:00Z", true);
    private static final IquaAlert NEWER = new IquaAlert("a1", "low_salt", "Low salt", "Add salt", "warning",
        "2026-11-09T07:00:00Z", false);

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final SoftenerReader reader = mock(SoftenerReader.class);
    private final IquaClient client = mock(IquaClient.class);
    private SoftenerAlertService sut;

    @BeforeEach
    void setUp() {
        when(reader.deviceId()).thenReturn(Optional.of("dev-1"));
        sut = new SoftenerAlertService(reader, client, SoftenerSamples.properties(URI.create("http://localhost:1")), clock);
    }

    @Test
    void should_answer_the_alerts_newest_first_in_the_time_of_the_house() {
        when(client.alerts("dev-1", SoftenerAlertService.PER_PAGE)).thenReturn(Mono.just(List.of(OLDER, NEWER)));

        sut.alerts().as(StepVerifier::create)
            .expectNext(List.of(
                new SoftenerAlertReply("a1", "low_salt", "Low salt", "Add salt", "warning",
                    LocalDateTime.of(2026, 11, 9, 8, 0), false),
                new SoftenerAlertReply("a0", "service", "Service", "Call the dealer", "info",
                    LocalDateTime.of(2026, 11, 1, 8, 0), true)))
            .verifyComplete();
    }

    //an alert without its time, or with one that is not an instant, goes last
    @Test
    void should_put_an_alert_without_a_time_last() {
        final IquaAlert untimed = new IquaAlert("a2", null, "Odd", null, null, "yesterday", null);
        when(client.alerts("dev-1", SoftenerAlertService.PER_PAGE)).thenReturn(Mono.just(List.of(untimed, NEWER)));

        sut.alerts().as(StepVerifier::create)
            .assertNext(alerts -> assertThat(alerts).extracting(SoftenerAlertReply::id).containsExactly("a1", "a2"))
            .verifyComplete();
    }

    @Test
    void should_keep_the_alerts_for_one_interval_of_the_read() {
        when(client.alerts("dev-1", SoftenerAlertService.PER_PAGE)).thenReturn(Mono.just(List.of(NEWER)));

        sut.alerts().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        clock.moveBy(Duration.ofMinutes(4));
        sut.alerts().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        verify(client, times(1)).alerts("dev-1", SoftenerAlertService.PER_PAGE);

        clock.moveBy(Duration.ofMinutes(1));
        sut.alerts().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        verify(client, times(2)).alerts("dev-1", SoftenerAlertService.PER_PAGE);
    }

    //the requests that come while the cloud is read share that read
    @Test
    void should_share_one_read_between_requests_at_once() {
        when(client.alerts("dev-1", SoftenerAlertService.PER_PAGE))
            .thenReturn(Mono.just(List.of(NEWER)).delayElement(Duration.ofMillis(200)));

        Mono.zip(sut.alerts(), sut.alerts()).as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        verify(client, times(1)).alerts("dev-1", SoftenerAlertService.PER_PAGE);
    }

    @Test
    void should_be_unavailable_before_the_softener_was_found() {
        when(reader.deviceId()).thenReturn(Optional.empty());

        sut.alerts().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(ValveException.class)
                .hasMessage("The water softener has not been read from the iQua cloud yet"))
            .verify();
    }

    //a failure of the cloud is a fixed message, kept for the interval too - a dashboard polling while the cloud fails must
    //not call it at every poll - then read again
    @Test
    void should_keep_a_failure_of_the_cloud_for_one_interval_then_read_again() {
        when(client.alerts("dev-1", SoftenerAlertService.PER_PAGE))
            .thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 500 for the alerts", 500)))
            .thenReturn(Mono.just(List.of(NEWER)));

        sut.alerts().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(ValveException.class)
                .hasMessage("The iQua cloud did not answer the alerts of the softener"))
            .verify();
        clock.moveBy(Duration.ofMinutes(4));
        sut.alerts().as(StepVerifier::create).expectError(ValveException.class).verify();
        verify(client, times(1)).alerts("dev-1", SoftenerAlertService.PER_PAGE);

        clock.moveBy(Duration.ofMinutes(1));
        sut.alerts().as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }
}
