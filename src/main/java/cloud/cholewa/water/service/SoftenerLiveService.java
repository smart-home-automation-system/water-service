package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerLiveReply;
import cloud.cholewa.water.softener.IquaException;
import cloud.cholewa.water.softener.SoftenerLive;
import cloud.cholewa.water.softener.SoftenerReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

//The live stream of the softener for the water page (HAS-401): PUT opens it or extends the one open, DELETE closes it.
//503 SOFTENER_UNAVAILABLE before the softener was found on the account, 429 LIVE_THROTTLED when the cloud throttles it
//(never retried), 502 DEVICE_FAILED when the cloud refused it otherwise or the socket did not open - its status alone
@Slf4j
@Service
@RequiredArgsConstructor
public class SoftenerLiveService {

    private static final int TOO_MANY_REQUESTS = 429;

    private final SoftenerReader softenerReader;
    private final SoftenerLive softenerLive;
    private final Clock clock;

    public Mono<SoftenerLiveReply> open() {
        return Mono.defer(() -> Mono.justOrEmpty(softenerReader.deviceId()))
            .switchIfEmpty(Mono.error(ValveException::softenerUnavailable))
            .flatMap(softenerLive::open)
            .map(until -> new SoftenerLiveReply(true, houseTime(until)))
            .onErrorMap(IquaException.class, failure -> {
                log.warn("The live stream of the softener was not opened: {}", failure.getMessage());
                return failure.getStatus() != null && failure.getStatus() == TOO_MANY_REQUESTS
                    ? ValveException.liveThrottled()
                    : ValveException.liveFailed(failure.getStatus());
            });
    }

    public Mono<Void> close() {
        return Mono.fromRunnable(softenerLive::close);
    }

    private LocalDateTime houseTime(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone()).truncatedTo(ChronoUnit.SECONDS);
    }
}
