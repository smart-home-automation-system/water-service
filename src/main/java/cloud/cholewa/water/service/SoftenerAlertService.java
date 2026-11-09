package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerAlertReply;
import cloud.cholewa.water.softener.IquaAlert;
import cloud.cholewa.water.softener.IquaClient;
import cloud.cholewa.water.softener.SoftenerProperties;
import cloud.cholewa.water.softener.SoftenerReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The alerts the iQua cloud keeps of the softener (HAS-399), for the alerts card of the dashboard: the first page of the
//alerts of the device (PER_PAGE), read on demand and kept for one interval of the read of the softener - the cloud blocks an
//account above about 1000 calls a day; the requests that come while it is read share the read. Newest first, the times in
//the time of the house. 503 SOFTENER_UNAVAILABLE before the softener was found on the account, 502 DEVICE_FAILED when the
//cloud did not answer - kept for the interval too
@Slf4j
@Service
public class SoftenerAlertService {

    static final int PER_PAGE = 20;

    private final SoftenerReader softenerReader;
    private final IquaClient iquaClient;
    private final SoftenerProperties softenerProperties;
    private final Clock clock;
    private final AtomicReference<Kept> kept = new AtomicReference<>();
    private final AtomicReference<Mono<List<SoftenerAlertReply>>> reading = new AtomicReference<>();

    public SoftenerAlertService(
        final SoftenerReader softenerReader,
        final IquaClient iquaClient,
        final SoftenerProperties softenerProperties,
        final Clock clock
    ) {
        this.softenerReader = softenerReader;
        this.iquaClient = iquaClient;
        this.softenerProperties = softenerProperties;
        this.clock = clock;
    }

    //the alerts of one softener and when they were read; alerts null - the read failed, kept as such too: a dashboard that
    //polls while the cloud fails would otherwise call it at every poll, past what the account may
    private record Kept(String deviceId, Instant at, List<SoftenerAlertReply> alerts) {
    }

    public Mono<List<SoftenerAlertReply>> alerts() {
        return Mono.defer(() -> Mono.justOrEmpty(softenerReader.deviceId())
            .switchIfEmpty(Mono.error(ValveException::softenerUnavailable))
            .flatMap(deviceId -> Optional.ofNullable(kept.get())
                .filter(held -> held.deviceId().equals(deviceId))
                .filter(held -> held.at().plus(softenerProperties.cloud().interval()).isAfter(clock.instant()))
                .map(held -> Mono.justOrEmpty(held.alerts()).switchIfEmpty(Mono.error(ValveException::softenerCloudFailed)))
                .orElseGet(() -> read(deviceId))));
    }

    private Mono<List<SoftenerAlertReply>> read(final String deviceId) {
        return reading.updateAndGet(current -> Optional.ofNullable(current).orElseGet(() -> iquaClient.alerts(deviceId, PER_PAGE)
            .map(this::replies)
            .doOnNext(alerts -> kept.set(new Kept(deviceId, clock.instant(), alerts)))
            .onErrorMap(failure -> !(failure instanceof ValveException), failure -> {
                log.warn("The alerts of the softener were not read from the iQua cloud: {}", failure.getMessage());
                kept.set(new Kept(deviceId, clock.instant(), null));
                return ValveException.softenerCloudFailed();
            })
            .doFinally(signal -> reading.set(null))
            .cache()));
    }

    private List<SoftenerAlertReply> replies(final List<IquaAlert> alerts) {
        return alerts.stream()
            .map(alert -> new SoftenerAlertReply(alert.id(), alert.type(), alert.title(), alert.message(), alert.level(),
                houseTime(alert.timestamp()), alert.read()))
            .sorted(Comparator.comparing(SoftenerAlertReply::at, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private LocalDateTime houseTime(final String timestamp) {
        try {
            return timestamp == null ? null
                : LocalDateTime.ofInstant(Instant.parse(timestamp), clock.getZone()).truncatedTo(ChronoUnit.SECONDS);
        } catch (final DateTimeParseException notInstant) {
            return null;
        }
    }
}
