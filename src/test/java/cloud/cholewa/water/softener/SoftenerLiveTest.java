package cloud.cholewa.water.softener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The live stream (HAS-401): opened at the address the cloud gives on its host, held for live-for, extended by a second open,
//closed at the end of its time, by a close and at the shutdown; the frames of the flow update the reading
class SoftenerLiveTest {

    private static final Duration LIVE_FOR = Duration.ofMinutes(5);

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final IquaClient client = mock(IquaClient.class);
    private final SoftenerTracker tracker = new SoftenerTracker();
    private final List<URI> opened = new ArrayList<>();
    private final AtomicInteger cancelled = new AtomicInteger();
    private Sinks.Many<String> frames;
    private boolean handshake = true;
    private VirtualTimeScheduler time;
    private SoftenerLive sut;

    //a socket of the cloud: the frames pushed by the test, its end seen as a cancel
    private final WebSocketClient socket = new WebSocketClient() {
        @Override
        public Mono<Void> execute(final URI url, final org.springframework.web.reactive.socket.WebSocketHandler handler) {
            return execute(url, null, handler);
        }

        @Override
        public Mono<Void> execute(final URI url, final org.springframework.http.HttpHeaders headers,
                                  final org.springframework.web.reactive.socket.WebSocketHandler handler) {
            opened.add(url);
            if (!handshake) {
                return Mono.error(new IllegalStateException("handshake refused"));
            }
            final WebSocketSession session = mock(WebSocketSession.class);
            when(session.receive()).thenReturn(frames.asFlux().map(text -> new WebSocketMessage(
                WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
                text.getBytes(StandardCharsets.UTF_8)))));
            return handler.handle(session).doOnCancel(cancelled::incrementAndGet);
        }
    };

    @BeforeEach
    void setUp() {
        time = VirtualTimeScheduler.getOrSet();
        frames = Sinks.many().multicast().onBackpressureBuffer();
        when(client.liveUri("dev-1")).thenReturn(Mono.just("/live/ws?p=s3cr3t-token"));
        sut = new SoftenerLive(client, tracker, socket, SoftenerSamples.JSON,
            SoftenerSamples.properties(URI.create("https://api.example.test/v1")), clock);
        tracker.read(new SoftenerReading(true, null, null, null, null, 0.0, null, null, null, null, null, null, null,
            new SoftenerReading.Alerts(null, null, null, null, null, null)), LocalDateTime.of(2026, 11, 9, 10, 0),
            clock.instant());
    }

    @AfterEach
    void tearDown() {
        sut.close();
        VirtualTimeScheduler.reset();
    }

    @Test
    void should_open_the_socket_on_the_host_of_the_cloud_for_live_for() {
        sut.open("dev-1").as(StepVerifier::create)
            .expectNext(SoftenerSamples.START.plus(LIVE_FOR))
            .verifyComplete();

        assertThat(opened).containsExactly(URI.create("wss://api.example.test/live/ws?p=s3cr3t-token"));
        assertThat(sut.until()).contains(SoftenerSamples.START.plus(LIVE_FOR));
    }

    //a frame of the flow updates the reading - its flow and its time
    @Test
    void should_update_the_flow_from_a_frame() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        frames.tryEmitNext("""
            {"name":"current_water_flow_gpm","type":"property","value":32,
             "converted_property":{"value":12.113318,"units":"liters/min"},"timestamp":"2026-11-09T09:00:30Z"}""");

        assertThat(tracker.current().reading().flowLitresPerMinute()).isEqualTo(12.1);
        assertThat(tracker.current().measuredAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0, 30));
    }

    @Test
    void should_close_the_socket_after_live_for() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        time.advanceTimeBy(LIVE_FOR.minusSeconds(1));
        assertThat(cancelled).hasValue(0);
        time.advanceTimeBy(Duration.ofSeconds(1));

        assertThat(cancelled).hasValue(1);
        assertThat(sut.until()).isEmpty();
    }

    //a second open extends the stream from then - one socket, one address read
    @Test
    void should_extend_the_stream_with_a_second_open() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();
        time.advanceTimeBy(Duration.ofMinutes(4));
        clock.moveBy(Duration.ofMinutes(4));

        sut.open("dev-1").as(StepVerifier::create)
            .expectNext(SoftenerSamples.START.plus(Duration.ofMinutes(4)).plus(LIVE_FOR))
            .verifyComplete();
        time.advanceTimeBy(Duration.ofMinutes(4));

        assertThat(cancelled).hasValue(0);
        time.advanceTimeBy(Duration.ofMinutes(1));
        assertThat(cancelled).hasValue(1);
        assertThat(opened).hasSize(1);
        verify(client, times(1)).liveUri(anyString());
    }

    @Test
    void should_close_the_socket_on_a_close() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        sut.close();

        assertThat(cancelled).hasValue(1);
        assertThat(sut.until()).isEmpty();
        time.advanceTimeBy(LIVE_FOR);
        assertThat(cancelled).hasValue(1);
    }

    @Test
    void should_close_the_socket_at_the_shutdown() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        sut.shutdown();

        assertThat(cancelled).hasValue(1);
    }

    //a socket the cloud closes is forgotten - the next open reads a new address
    @Test
    void should_forget_a_stream_the_cloud_closed() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        frames.tryEmitComplete();

        assertThat(sut.until()).isEmpty();
    }

    @Test
    void should_fail_an_open_whose_handshake_failed() {
        handshake = false;

        sut.open("dev-1").as(StepVerifier::create)
            .expectErrorMessage("The live stream of the softener did not open")
            .verify();

        assertThat(sut.until()).isEmpty();
    }

    //the throttle of the cloud goes to the caller as it came - never retried
    @Test
    void should_pass_the_throttle_of_the_cloud() {
        when(client.liveUri("dev-1")).thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 429 for "
            + "the live stream", 429)));

        sut.open("dev-1").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(((IquaException) failure).getStatus()).isEqualTo(429))
            .verify();

        assertThat(opened).isEmpty();
        assertThat(sut.until()).isEmpty();
    }

    //a frame older than the reading held changes nothing
    @Test
    void should_not_take_a_frame_older_than_the_reading() {
        sut.open("dev-1").as(StepVerifier::create).expectNextCount(1).verifyComplete();

        frames.tryEmitNext("""
            {"name":"current_water_flow_gpm","type":"property",
             "converted_property":{"value":5.0,"units":"liters/min"},"timestamp":"2026-11-09T08:59:00Z"}""");

        assertThat(tracker.current().reading().flowLitresPerMinute()).isZero();
        assertThat(Instant.parse("2026-11-09T08:59:00Z")).isBefore(SoftenerSamples.START);
    }
}
