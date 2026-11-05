package cloud.cholewa.water.registry;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.valve.ValveTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The water valve of the device registry (HAS-316), read at the start and every five minutes. A failed read is a WARN
//and keeps what was read before; until the first read succeeds nothing is known (GET /valve answers
//REGISTRY_UNAVAILABLE). None registered is nothing watched, logged when it becomes so; the house has one valve, so of
//more the one with the lowest id is read, with a WARN. Another valve than before forgets what was known of the old one
@Slf4j
@Component
@RequiredArgsConstructor
public class ValveRegistry {

    static final Duration LIMIT = Duration.ofMinutes(1);

    private final DeviceRegistryClient deviceRegistryClient;
    private final ValveTracker valveTracker;

    //what the last read found - its valve null when the registry has none the service can read
    private record Read(WaterValve valve) {
    }

    //null before the first read succeeded
    private final AtomicReference<Read> lastRead = new AtomicReference<>();

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("water valve registry refresh", LIMIT, () -> deviceRegistryClient.getWaterValves()
            .map(ValveRegistry::chosen)
            .doOnNext(this::replace)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The water valve of the registry could not be read, the last one is kept: {}", failure.toString()))));
    }

    //whether the registry was read since the start
    public boolean isKnown() {
        return lastRead.get() != null;
    }

    //the valve of the registry, or nothing - before the first read, and when none is registered
    public Optional<WaterValve> current() {
        return Optional.ofNullable(lastRead.get()).map(Read::valve);
    }

    //the valve a request acts on: REGISTRY_UNAVAILABLE until the registry was read once, NOT_FOUND_VALVE while it has
    //none (GET and POST /valve). Static, so that a mock of the registry answers it by its two reads
    public static Mono<WaterValve> known(final ValveRegistry registry) {
        return Mono.fromSupplier(registry::isKnown)
            .filter(Boolean.TRUE::equals)
            .switchIfEmpty(Mono.error(ValveException::registryUnavailable))
            .flatMap(read -> Mono.justOrEmpty(registry.current()))
            .switchIfEmpty(Mono.error(ValveException::notFound));
    }

    private void replace(final Read read) {
        final Read before = lastRead.getAndSet(read);
        if (read.equals(before)) {
            return;
        }
        if (read.valve() == null) {
            log.info("No water valve is registered, none is watched");
        } else {
            log.info("The water valve of the registry is device {} ({})", read.valve().id(), read.valve().deviceId());
        }
        if (!Objects.equals(deviceOf(before), deviceOf(read))) {
            valveTracker.reset();
        }
    }

    private static String deviceOf(final Read read) {
        return read == null || read.valve() == null ? null : read.valve().deviceId();
    }

    private static Read chosen(final List<SmartDevice> devices) {
        final List<WaterValve> valves = devices.stream()
            .map(device -> WaterValve.of(device).or(() -> {
                log.warn("Device {} of the registry is no water valve the service can read", device.getId());
                return Optional.empty();
            }))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(WaterValve::id, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        if (valves.size() > 1) {
            log.warn("The registry has {} water valves, the house has one: device {} is read", valves.size(),
                valves.getFirst().id());
        }
        return new Read(valves.isEmpty() ? null : valves.getFirst());
    }
}
