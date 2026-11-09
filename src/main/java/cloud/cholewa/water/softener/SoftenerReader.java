package cloud.cholewa.water.softener;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

//Reads the softener from the iQua cloud every water.softener.cloud.interval (HAS-397), bounded at LIMIT: the devices of the
//account at the first pass and once a day (the softener of water.softener.serial-number, the first one without it), then
//the detail of that one - one data call a pass, far below the about 1000 calls a day the cloud allows. No softener on the
//account is unreachable at once, a failed read a missed pass (SoftenerTracker); one WARN an hour per kind of failure
//(FailureLog), an INFO at the return. A property the reading does not know is logged once at DEBUG
@Slf4j
@Component
public class SoftenerReader {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration LIST_EVERY = Duration.ofDays(1);
    private static final Set<Integer> GONE = Set.of(403, 404);
    private static final String WHAT = "iQua cloud";

    private final IquaClient iquaClient;
    private final SoftenerTracker softenerTracker;
    private final SoftenerProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    private final AtomicReference<Listed> listed = new AtomicReference<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    public SoftenerReader(
        final IquaClient iquaClient,
        final SoftenerTracker softenerTracker,
        final SoftenerProperties properties,
        final Clock clock
    ) {
        this.iquaClient = iquaClient;
        this.softenerTracker = softenerTracker;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    //the softener picked from the devices of the account, and when they were listed
    private record Listed(String deviceId, Instant at) {
    }

    //timeUnit: a bare number of the interval is minutes here too, as SoftenerProperties binds it
    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "${water.softener.cloud.interval}",
        timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    Mono<Void> read() {
        return ScheduledPass.bounded("softener read", LIMIT, () -> Mono.defer(this::softener)
            .flatMap(this::read)
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                softenerTracker.missed();
                failureLog.failed(WHAT, failure);
            }))
            //a pass cut off by the bound - cancelled before it ended - is a missed one too
            .doOnCancel(softenerTracker::missed)
            .then());
    }

    //the softener listed within a day, or the devices listed now; empty - the account has no softener to read
    private Mono<String> softener() {
        return Mono.justOrEmpty(Optional.ofNullable(listed.get())
                .filter(held -> held.at().plus(LIST_EVERY).isAfter(clock.instant()))
                .map(Listed::deviceId))
            .switchIfEmpty(Mono.defer(() -> iquaClient.devices().flatMap(this::pick)));
    }

    private Mono<String> pick(final List<IquaDevice> devices) {
        final Optional<String> serialNumber = Optional.ofNullable(properties.serialNumber()).filter(serial -> !serial.isBlank());
        final Optional<IquaDevice> softener = devices.stream()
            .filter(device -> device.id() != null)
            .filter(device -> serialNumber.map(serial -> serial.equals(device.serialNumber())).orElse(true))
            .findFirst();
        softener.ifPresentOrElse(
            device -> {
                listed.set(new Listed(device.id(), clock.instant()));
                log.info("The softener of the iQua account: {} ({}, {})", device.id(), device.systemType(),
                    device.nickname());
            },
            () -> {
                listed.set(null);
                softenerTracker.noSoftener();
                failureLog.failed(WHAT, new IquaException(serialNumber.isEmpty()
                    ? "The iQua account has no softener"
                    : "The iQua account has no softener of the configured serial number"));
            });
        return Mono.justOrEmpty(softener.map(IquaDevice::id));
    }

    //a softener the cloud no longer knows by that id (404) or no longer shows to the account (403) is listed anew next pass
    private Mono<Void> read(final String deviceId) {
        return iquaClient.detail(deviceId)
            .doOnNext(device -> {
                logUnknown(device);
                softenerTracker.read(SoftenerReading.of(device),
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS), clock.instant());
                failureLog.succeeded(WHAT);
            })
            .doOnError(IquaException.class, failure -> Optional.ofNullable(failure.getStatus())
                .filter(GONE::contains)
                .ifPresent(status -> listed.set(null)))
            .then();
    }

    private void logUnknown(final IquaDevice device) {
        Optional.ofNullable(device.properties()).stream()
            .flatMap(map -> map.keySet().stream())
            .filter(Objects::nonNull)
            .filter(name -> !SoftenerReading.KNOWN.contains(name))
            .filter(unknown::add)
            .forEach(name -> log.debug("The softener reports a property the service does not read: {}", name));
    }
}
