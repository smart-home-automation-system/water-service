package cloud.cholewa.water.softener;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.database.repository.SoftenerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//Reads the softener from the iQua cloud every water.softener.cloud.interval (HAS-397), bounded at LIMIT: the devices of the
//account at the first pass and once a day (the softener of water.softener.serial-number, the first one without it), then
//the detail of that one - one data call a pass, far below the about 1000 calls a day the cloud allows. No softener on the
//account is unreachable at once, a failed read a missed pass (SoftenerTracker); one WARN an hour per kind of failure
//(FailureLog), an INFO at the return. A property the reading does not know is logged once at DEBUG. Every reading is
//stored in softener_reading (HAS-398) - a row on a change, last_checked_at moved otherwise; a failed write is a WARN and
//never a missed read
@Slf4j
@Component
public class SoftenerReader {

    static final Duration LIMIT = Duration.ofSeconds(50);
    static final Duration LIST_EVERY = Duration.ofDays(1);
    private static final Set<Integer> GONE = Set.of(403, 404);
    private static final String WHAT = "iQua cloud";
    private static final long SECONDS_A_DAY = 86_400;

    private final IquaClient iquaClient;
    private final SoftenerTracker softenerTracker;
    private final SoftenerRepository softenerRepository;
    private final SoftenerProperties properties;
    private final Clock clock;
    private final FailureLog failureLog;
    private final AtomicReference<Listed> listed = new AtomicReference<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    public SoftenerReader(
        final IquaClient iquaClient,
        final SoftenerTracker softenerTracker,
        final SoftenerRepository softenerRepository,
        final SoftenerProperties properties,
        final Clock clock
    ) {
        this.iquaClient = iquaClient;
        this.softenerTracker = softenerTracker;
        this.softenerRepository = softenerRepository;
        this.properties = properties;
        this.clock = clock;
        this.failureLog = new FailureLog(log, clock);
    }

    //the softener picked from the devices of the account, and when they were listed
    private record Listed(String deviceId, Instant at) {
    }

    //the id of the softener in the cloud, once the devices of the account were listed - read by SoftenerRegenerations
    public Optional<String> deviceId() {
        return Optional.ofNullable(listed.get()).map(Listed::deviceId);
    }

    //timeUnit: a bare number of the interval is minutes here too, as SoftenerProperties binds it
    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "${water.softener.cloud.interval}",
        timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    Mono<Void> read() {
        return ScheduledPass.bounded("softener read", LIMIT, () -> Mono.defer(() -> {
            final AtomicBoolean arrived = new AtomicBoolean();
            return softener()
                .flatMap(deviceId -> read(deviceId, arrived))
                .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    softenerTracker.missed();
                    failureLog.failed(WHAT, failure);
                }))
                //a pass cut off by the bound before its reading arrived is a missed one too - one cut while the reading
                //is stored is not
                .doOnCancel(() -> {
                    if (!arrived.get()) {
                        softenerTracker.missed();
                    }
                })
                .then();
        }));
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
    private Mono<Void> read(final String deviceId, final AtomicBoolean arrived) {
        return iquaClient.detail(deviceId)
            .map(device -> {
                arrived.set(true);
                logUnknown(device);
                final SoftenerReading reading = SoftenerReading.of(device);
                final LocalDateTime at = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
                softenerTracker.read(reading, at, clock.instant());
                regeneration(device, reading.daysSinceRegeneration(), at);
                failureLog.succeeded(WHAT);
                return new Read(reading, at);
            })
            .doOnError(IquaException.class, failure -> Optional.ofNullable(failure.getStatus())
                .filter(GONE::contains)
                .ifPresent(status -> listed.set(null)))
            .flatMap(this::store);
    }

    private record Read(SoftenerReading reading, LocalDateTime at) {
    }

    //the regeneration (HAS-399, HAS-400): enriched_data.water_treatment.regeneration_status - the words of the cloud (none,
    //scheduled, ...); regen_status_enum - 0 none, 1 scheduled - with its updated_at, the time the cloud changed it; regen_time_secs - the time of day of the regeneration in seconds. A property left out or
    //not a number tells nothing
    private void regeneration(final IquaDevice device, final Integer daysSince, final LocalDateTime at) {
        final Map<String, IquaProperty> reported = device.properties();
        final Optional<IquaProperty> status = Optional.ofNullable(reported.get("regen_status_enum"));
        final LocalDateTime changedAt = status.map(IquaProperty::updatedAt)
            .flatMap(this::houseTime)
            .orElse(null);
        final LocalTime scheduledAt = Optional.ofNullable(reported.get("regen_time_secs"))
            .flatMap(SoftenerReader::whole)
            .filter(seconds -> seconds >= 0)
            .map(seconds -> LocalTime.ofSecondOfDay(seconds % SECONDS_A_DAY))
            .orElse(null);
        final String words = Optional.ofNullable(device.enrichedData())
            .map(enriched -> enriched.path("water_treatment").path("regeneration_status"))
            .filter(JsonNode::isString)
            .map(JsonNode::asString)
            .filter(phase -> !phase.isBlank())
            .orElse(null);
        softenerTracker.regeneration(words, status.flatMap(SoftenerReader::whole).map(Long::intValue).orElse(null),
            daysSince, changedAt, scheduledAt, at);
    }

    private static Optional<Long> whole(final IquaProperty property) {
        return Optional.ofNullable(property.value())
            .filter(JsonNode::isIntegralNumber)
            .map(JsonNode::asLong);
    }

    private Optional<LocalDateTime> houseTime(final String instant) {
        try {
            return Optional.of(LocalDateTime.ofInstant(Instant.parse(instant), clock.getZone()).truncatedTo(ChronoUnit.SECONDS));
        } catch (final RuntimeException notInstant) {
            return Optional.empty();
        }
    }

    private Mono<Void> store(final Read read) {
        return softenerRepository.recordReading(read.reading(), read.at())
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The reading of the softener could not be stored: {}", failure.toString())));
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
