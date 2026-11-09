package cloud.cholewa.water.softener;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

//The live stream of the softener (HAS-401): the cloud refreshes the flow only while a client keeps its stream open, so a
//PUT /softener/live opens it - GET /devices/{id}/live, then the WebSocket at its websocket_uri on the host of the cloud -
//and holds it for water.softener.live-for. One at most: a second open extends it; a close, the end of its time and the
//shutdown of the pod close it. Every frame of the flow (LiveFrame) updates the reading in memory (SoftenerTracker.live);
//nothing is stored - the reads of every five minutes keep the history. The address carries a token of its own and is never
//logged; the socket is not built on the WebClient, so Logbook does not see it either. A socket that ends of itself or fails
//is one WARN and is not opened again by itself. The stream is throttled by the cloud: a 429 of /live goes to the caller as
//IquaException(429), never retried. Pinned by SoftenerLiveTest
@Slf4j
@Component
public class SoftenerLive {

    //the handshake of the socket, before the open is answered as failed
    static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

    private final IquaClient iquaClient;
    private final SoftenerTracker softenerTracker;
    private final WebSocketClient webSocketClient;
    private final JsonMapper jsonMapper;
    private final SoftenerProperties properties;
    private final Clock clock;
    //guarded by this
    private Session session;

    public SoftenerLive(
        final IquaClient iquaClient,
        final SoftenerTracker softenerTracker,
        final WebSocketClient iquaWebSocketClient,
        final JsonMapper jsonMapper,
        final SoftenerProperties properties,
        final Clock clock
    ) {
        this.iquaClient = iquaClient;
        this.softenerTracker = softenerTracker;
        this.webSocketClient = iquaWebSocketClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
    }

    //one stream: its subscription, the end of its time and when that is; opened - the handshake, true once it was made
    private static final class Session {

        private final Sinks.One<Boolean> opened = Sinks.one();
        private Disposable socket;
        private Disposable timer;
        private Instant until;
    }

    //when the stream open now ends, empty while none is open
    public synchronized Optional<Instant> until() {
        return Optional.ofNullable(session).map(held -> held.until);
    }

    //opens the stream of the softener, or extends the one open; answers when it ends
    public Mono<Instant> open(final String deviceId) {
        return Mono.defer(() -> extend()
            .map(Mono::just)
            .orElseGet(() -> iquaClient.liveUri(deviceId).flatMap(this::connect)));
    }

    //closes the stream open, if any
    public synchronized void close() {
        Optional.ofNullable(session).ifPresent(held -> {
            session = null;
            dispose(held.timer);
            dispose(held.socket);
        });
    }

    @PreDestroy
    void shutdown() {
        close();
    }

    private synchronized Optional<Instant> extend() {
        return Optional.ofNullable(session).map(held -> {
            dispose(held.timer);
            schedule(held);
            log.info("The live stream of the softener extended until {}", held.until);
            return held.until;
        });
    }

    private Mono<Instant> connect(final String path) {
        final Session opening;
        synchronized (this) {
            //another open came while the address was read: that stream is extended, the new address left unused
            if (session != null) {
                return Mono.justOrEmpty(extend());
            }
            opening = new Session();
            session = opening;
            schedule(opening);
            opening.socket = webSocketClient.execute(socketUri(path), webSocket -> {
                    opening.opened.tryEmitValue(true);
                    return webSocket.receive()
                        .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(this::apply)
                        .then();
                })
                .subscribe(
                    done -> { },
                    failure -> ended(opening, "failed: " + failure.getClass().getSimpleName()),
                    () -> ended(opening, "was closed by the cloud"));
            if (session != opening) {
                //it ended while it was being subscribed to
                opening.socket.dispose();
            }
        }
        return opening.opened.asMono()
            .timeout(OPEN_TIMEOUT)
            .filter(Boolean.TRUE::equals)
            .map(open -> opening.until)
            .switchIfEmpty(Mono.error(() -> new IquaException("The live stream of the softener did not open")))
            .doOnNext(until -> log.info("The live stream of the softener is open until {}", until))
            .doOnError(failure -> closeIf(opening));
    }

    //the end of the time of a session from now - only of that session, should another have come meanwhile
    private void schedule(final Session held) {
        held.until = clock.instant().plus(properties.liveFor());
        held.timer = Mono.delay(properties.liveFor()).subscribe(
            tick -> {
                if (closeIf(held)) {
                    log.info("The live stream of the softener closed after {}", properties.liveFor());
                }
            },
            failure -> log.warn("The end of the live stream of the softener failed: {}", failure.toString()));
    }

    private synchronized boolean closeIf(final Session held) {
        if (session != held) {
            return false;
        }
        close();
        return true;
    }

    //the socket ended of itself - a close disposes it without a signal: one WARN, the session forgotten, and an open that
    //waits for the handshake answered as failed
    private synchronized void ended(final Session held, final String how) {
        held.opened.tryEmitValue(false);
        if (session == held) {
            log.warn("The live stream of the softener {}", how);
            session = null;
            dispose(held.timer);
        }
    }

    private static void dispose(final Disposable disposable) {
        Optional.ofNullable(disposable).ifPresent(Disposable::dispose);
    }

    private void apply(final String text) {
        LiveFrame.flow(text, jsonMapper).ifPresent(frame -> softenerTracker.live(frame.litresPerMinute(),
            LocalDateTime.ofInstant(Optional.ofNullable(frame.at()).orElseGet(clock::instant), clock.getZone())
                .truncatedTo(ChronoUnit.SECONDS)));
    }

    //wss on the host of the cloud (ws for a test server on http), the path and the query the cloud gave
    private URI socketUri(final String path) {
        final URI base = properties.cloud().baseUrl();
        final String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + base.getRawAuthority() + path);
    }
}
