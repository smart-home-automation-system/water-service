package cloud.cholewa.water.valve;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ObjectStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

//Reads the virtual components of the water valve over its RPC ten seconds after the start and every minute after the
//last read (HAS-316): Shelly.GetComponents with dynamic_only, one page of five on the valve. It keeps the state current
//when MQTT is silent, and after a gap of the broker its values replace the old ones. The calls have the timeouts of
//shelly-client (shelly.client.*, 3 s to connect, 5 s of silence); the whole pass is bounded below the minute between two
//passes. A failed read changes nothing - the values age until GET /valve leaves them out - and is a WARN when it starts
//failing, an INFO when it answers again, so a valve that is off does not write a WARN a minute. A component whose status
//is not its model is skipped, with the same WARN when it starts and INFO when it ends
@Slf4j
@Component
@RequiredArgsConstructor
public class ValveReconciliation {

    static final Duration LIMIT = Duration.ofSeconds(45);

    private static final Map<String, Class<?>> MODELS = Map.of(
        "boolean", BooleanStatus.class,
        "number", NumberStatus.class,
        "object", ObjectStatus.class);

    private final ValveRegistry valveRegistry;
    private final ShellyRpcClient shellyRpcClient;
    private final ValveTracker valveTracker;
    private final JsonMapper jsonMapper;
    private final AtomicBoolean failing = new AtomicBoolean();
    //the components whose status was not their model at the last read - one WARN when it starts, an INFO when it ends
    private final Set<String> notModels = ConcurrentHashMap.newKeySet();

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT1M")
    Mono<Void> reconcile() {
        return ScheduledPass.bounded("water valve reconciliation", LIMIT,
            () -> Mono.defer(() -> Mono.justOrEmpty(valveRegistry.current())).flatMap(this::read));
    }

    //one read now, outside the schedule - asked after a command of the valve (HAS-321), so that a valve that publishes
    //nothing for a state it was already in answers it too; bounded as a pass, a failure is the read's own
    public Mono<Void> readNow() {
        return Mono.defer(() -> Mono.justOrEmpty(valveRegistry.current()))
            .flatMap(this::read)
            .timeout(LIMIT, Mono.empty());
    }

    private Mono<Void> read(final WaterValve valve) {
        return shellyRpcClient.dynamicComponents(valve.address())
            .doOnNext(this::report)
            .doOnNext(components -> answered(valve))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> failed(valve, failure)));
    }

    private void report(final List<ShellyComponent> components) {
        components.forEach(component -> keyOf(component.getKey())
            .ifPresent(key -> statusOf(component, key).ifPresent(status -> valveTracker.report(key.index(), status))));
    }

    //the status of a component the house reads, as its model; nothing for one without the time of its value - "{}"
    //decodes into a model of nulls - and for one that is not its model at all
    private Optional<Object> statusOf(final ShellyComponent component, final Key key) {
        try {
            final Object status = jsonMapper.convertValue(component.getStatus(), MODELS.get(key.type()));
            if (notModels.remove(component.getKey())) {
                log.info("The status of {} of the water valve is its model again", component.getKey());
            }
            return Optional.ofNullable(status)
                .filter(ValveReconciliation::hasTime);
        } catch (JacksonException | IllegalArgumentException notTheModel) {
            if (notModels.add(component.getKey())) {
                log.warn("The status of {} of the water valve is not its model: {}", component.getKey(),
                    notTheModel.getMessage());
            }
            return Optional.empty();
        }
    }

    private static boolean hasTime(final Object status) {
        return switch (status) {
            case BooleanStatus value -> value.getLastUpdateTs() != null;
            case NumberStatus value -> value.getLastUpdateTs() != null;
            case ObjectStatus value -> value.getLastUpdateTs() != null;
            default -> false;
        };
    }

    //"number:201" as its type and id; nothing for a key of a component the house does not read
    private static Optional<Key> keyOf(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final int colon = key.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        try {
            final Key parsed = new Key(key.substring(0, colon), Integer.parseInt(key.substring(colon + 1)));
            return Optional.of(parsed).filter(read -> ValveTracker.isRead(read.type(), read.index()));
        } catch (NumberFormatException notAnId) {
            return Optional.empty();
        }
    }

    private record Key(String type, int index) {
    }

    private void answered(final WaterValve valve) {
        if (failing.getAndSet(false)) {
            log.info("The water valve {} answers its read again", valve.address());
        }
    }

    private void failed(final WaterValve valve, final Throwable failure) {
        if (!failing.getAndSet(true)) {
            log.warn("The water valve {} could not be read: {}", valve.address(), failure.toString());
        } else {
            log.debug("The water valve {} still cannot be read: {}", valve.address(), failure.toString());
        }
    }
}
