package cloud.cholewa.water.circulation;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.water.circulation.RoomTriggers.Trigger;
import cloud.cholewa.water.light.LightClient;
import cloud.cholewa.water.presence.HouseOccupancy;
import cloud.cholewa.water.presence.PresenceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

//The automation of the circulation pump (HAS-405), taking over from the scene "CONTROL WATER CIRCULATION" of the Shelly
//cloud: a run before the tap is opened - never on the flow, the water then runs already (owner, 2026-11-05).
//Every PASS_EVERY (10 s) the rooms of water.circulation.automation.rooms are read from light-service one after the other
//(RoomTriggers: a new motion, a light switched on), and once a minute of a comfort window is a trigger too. The first
//trigger of a pass is judged by AutomationRules - the state in memory, then the pause (one statement), then the house
//(presence-service, unknown never at home) - and starts a run (CirculationService.startAutomatic). A trigger that is
//not allowed is a DEBUG line; a start, an INFO; a refused one (a cold tank, a Uni that did not answer), a WARN.
//A room light-service does not answer is one WARN when it starts and an INFO on its return, per room; it triggers nothing.
//Nothing is read while water.circulation.automation.enabled is false. fixedDelay: a pass waits for the one before,
//bounded at LIMIT - two reads of light-service, the statement, presence-service and a start fit
@Slf4j
@Component
@RequiredArgsConstructor
public class CirculationAutomation {

    static final Duration LIMIT = Duration.ofSeconds(45);
    static final Duration LIGHT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration PRESENCE_TIMEOUT = Duration.ofSeconds(10);
    static final String WINDOW = "window";

    private final LightClient lightClient;
    private final PresenceClient presenceClient;
    private final CirculationService circulationService;
    private final CirculationPump pump;
    private final AutomationProperties automation;
    private final CirculationProperties circulation;
    private final Clock clock;

    private final RoomTriggers triggers = new RoomTriggers();
    private final AtomicReference<LocalDateTime> windowMinute = new AtomicReference<>();
    private final Set<String> silentRooms = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT20S")
    Mono<Void> watch() {
        return ScheduledPass.bounded("circulation automation", LIMIT, () -> Mono.defer(this::pass));
    }

    Mono<Void> pass() {
        return Mono.just(automation.enabled())
            .filter(Boolean::booleanValue)
            .flatMap(on -> Mono.fromSupplier(this::now))
            .flatMap(now -> roomTrigger()
                .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(windowTrigger(now))))
                .flatMap(trigger -> decide(trigger, now)));
    }

    //the first trigger of the rooms, read one after the other; every room is read, so each keeps what it last showed
    private Mono<Trigger> roomTrigger() {
        return Flux.fromIterable(automation.rooms())
            .concatMap(room -> lightClient.room(room)
                .timeout(LIGHT_TIMEOUT)
                .doOnNext(read -> lightHeard(room))
                .flatMap(read -> Mono.justOrEmpty(triggers.observe(room, read)))
                .onErrorResume(failure -> Mono.fromRunnable(() -> lightUnheard(room, failure))))
            .collectList()
            .flatMap(found -> Mono.justOrEmpty(found.stream().findFirst()));
    }

    //once a minute of a comfort window - the first pass of each minute inside one takes the minute
    private Optional<Trigger> windowTrigger(final LocalDateTime now) {
        final Optional<AutomationProperties.Window> window = automation.comfortWindows().stream()
            .filter(each -> each.contains(now.toLocalTime()))
            .findFirst();
        if (window.isEmpty()) {
            return Optional.empty();
        }
        final LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        final LocalDateTime before = windowMinute.getAndSet(minute);
        return minute.equals(before) ? Optional.empty() : window.map(open -> new Trigger(WINDOW, open.label()));
    }

    private Mono<Void> decide(final Trigger trigger, final LocalDateTime now) {
        return Mono.justOrEmpty(AutomationRules.blockedNow(now, pump.reading(), pump.run().isPresent(), automation, circulation))
            .switchIfEmpty(Mono.defer(() -> pause(now)))
            .switchIfEmpty(Mono.defer(this::house))
            .map(reason -> {
                log.debug("No run of the circulation for {} ({}): {}", trigger.kind(), trigger.source(), reason);
                return true;
            })
            .switchIfEmpty(Mono.defer(() -> start(trigger)))
            .then();
    }

    //a failed read of the table is no pause: the pause only spares heat, and the other conditions still hold
    private Mono<String> pause(final LocalDateTime now) {
        return circulationService.lastSuccessfulEnd()
            .flatMap(ended -> Mono.justOrEmpty(AutomationRules.paused(now, ended, automation.pauseAfter())))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The last run of the circulation was not read for its pause: {}", failure.toString())));
    }

    private Mono<String> house() {
        return presenceClient.residents()
            .timeout(PRESENCE_TIMEOUT)
            .map(residents -> HouseOccupancy.judge(residents, now()))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.debug("presence-service was not read for the circulation: {}", failure.toString());
                return HouseOccupancy.UNKNOWN;
            }))
            .flatMap(house -> Mono.justOrEmpty(AutomationRules.nobodyHome(house)));
    }

    private Mono<Boolean> start(final Trigger trigger) {
        return circulationService.startAutomatic(trigger.kind(), trigger.source())
            .thenReturn(true)
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The circulation was not started for {} ({}): {}", trigger.kind(), trigger.source(),
                    failure.getMessage());
                return false;
            }));
    }

    private void lightHeard(final String room) {
        if (silentRooms.remove(room)) {
            log.info("light-service answers the circulation automation again ({})", room);
        }
    }

    private void lightUnheard(final String room, final Throwable failure) {
        if (silentRooms.add(room)) {
            log.warn("light-service was not read for the circulation ({}): {}", room, failure.toString());
        } else {
            log.debug("light-service still not read ({}): {}", room, failure.toString());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
