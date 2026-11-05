package cloud.cholewa.water.command;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.water.database.model.WaterValveCommandEntity;
import cloud.cholewa.water.database.repository.WaterValveCommandRepository;
import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.ValveCommandReply;
import cloud.cholewa.water.registry.ValveRegistry;
import cloud.cholewa.water.registry.WaterValve;
import cloud.cholewa.water.valve.ValveProperties;
import cloud.cholewa.water.valve.ValveReconciliation;
import cloud.cholewa.water.valve.ValveTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

//The command of the water valve (HAS-321) - the one command that cuts the water of the whole house: Boolean.Set of
//boolean:200 (true opens it) through shelly-client, sent once - never repeated, a 502 or a lost answer may still have
//been carried out. What the valve took is stored (water_valve_command) and waits for the report of the valve in that
//state (ValveCommands, MQTT or the read of every minute); the read is asked READ_AFTER after the command, so a valve that
//publishes nothing for a state it was already in is confirmed too. Nothing within ValveCommand.WINDOW: a WARN,
//lastCommandConfirmed false in GET /valve, the third in a row an alert (once a run, none while the valve is not heard -
//the offline alarm speaks for it). The refusals: INVALID_COMMAND, REGISTRY_UNAVAILABLE, NOT_FOUND_VALVE, DEVICE_FAILED -
//the status of the valve alone, its words to the log; a command not answered at all may have been carried out, so it is
//stored and waits for its report too
@Slf4j
@Service
@RequiredArgsConstructor
public class ValveCommandService {

    //the id of the valve among the virtual components of the Neo Smart Water Valve
    static final int VALVE = 200;
    //the timeouts of shelly-client (3 s to connect, 5 s of silence) and a bound of the whole call
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    static final Duration STORE_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_AFTER = Duration.ofSeconds(15);
    static final int ALERT_IN_A_ROW = 3;
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z-]{0,19}");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final ValveRegistry valveRegistry;
    private final ShellyRpcClient shellyRpcClient;
    private final WaterValveCommandRepository repository;
    private final ValveCommands valveCommands;
    private final ValveTracker valveTracker;
    private final ValveReconciliation valveReconciliation;
    private final NotificationPublisher notificationPublisher;
    private final ValveProperties valveProperties;
    private final Clock clock;

    //state - open or close; source - who asks, a short lowercase word (dashboard, rule)
    public Mono<ValveCommandReply> command(final String state, final String source) {
        return Mono.justOrEmpty(openOf(state))
            .switchIfEmpty(Mono.error(() -> ValveException.invalidCommand("The state is open or close, not " + state)))
            .filter(open -> source != null && SOURCE.matcher(source).matches())
            .switchIfEmpty(Mono.error(() -> ValveException.invalidCommand(
                "The source is a lowercase word of at most 20 letters or dashes, not " + source)))
            .flatMap(open -> ValveRegistry.known(valveRegistry).flatMap(valve -> send(valve, open, source)))
            .map(command -> new ValveCommandReply(command.state(), command.source(),
                command.at().truncatedTo(ChronoUnit.SECONDS)));
    }

    private static Optional<Boolean> openOf(final String state) {
        return Optional.ofNullable(state).map(text -> switch (text) {
            case "open" -> Boolean.TRUE;
            case "close" -> Boolean.FALSE;
            default -> null;
        });
    }

    private Mono<ValveCommand> send(final WaterValve valve, final boolean open, final String source) {
        return Mono.fromSupplier(this::now)
            .flatMap(at -> shellyRpcClient.booleanSet(valve.address(), VALVE, open)
                .timeout(COMMAND_TIMEOUT)
                .doOnSubscribe(subscription -> log.info("The water valve {} is commanded to {} by {}", valve.address(),
                    open ? "open" : "close", source))
                .then(Mono.defer(() -> taken(new ValveCommand(null, open, source, at))))
                .onErrorResume(failure -> failed(valve, new ValveCommand(null, open, source, at), failure)));
    }

    private Mono<ValveCommand> taken(final ValveCommand command) {
        return store(command).doOnNext(stored -> {
            valveCommands.issued(stored);
            confirm(stored);
        });
    }

    //refused: nothing was done. Not answered: it may have been carried out, so it is stored and waits for its report
    //like a taken one - the caller still gets the 502, and the read tells whether the valve did it
    private Mono<ValveCommand> failed(final WaterValve valve, final ValveCommand command, final Throwable failure) {
        final Integer status = failure instanceof ShellyCallException call ? call.httpStatus() : null;
        log.warn("The water valve {} did not take the command to {} by {}: {}", valve.address(), command.state(),
            command.source(), failure.toString());
        return Mono.just(command)
            .filter(unanswered -> status == null)
            .flatMap(this::taken)
            .then(Mono.error(ValveException.deviceFailed(status)));
    }

    //the command is taken whether its row is stored or not: what the valve did matters more than its log
    private Mono<ValveCommand> store(final ValveCommand command) {
        return repository.save(new WaterValveCommandEntity(null, command.at(), command.state(), command.source(), null))
            .timeout(STORE_TIMEOUT)
            .map(saved -> new ValveCommand(saved.id(), command.open(), command.source(), command.at()))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The command {} by {} of the water valve could not be stored: {}", command.state(),
                    command.source(), failure.toString());
                return command;
            }));
    }

    private void confirm(final ValveCommand command) {
        confirmation(command)
            .contextCapture()
            .subscribe(null, failure -> log.error("The confirmation of the command {} of the water valve ended with an "
                + "error", command.state(), failure));
    }

    Mono<Void> confirmation(final ValveCommand command) {
        return Mono.delay(READ_AFTER)
            .then(valveReconciliation.readNow())
            .then(Mono.delay(ValveCommand.WINDOW.minus(READ_AFTER)))
            .flatMap(tick -> Mono.justOrEmpty(valveCommands.expired(command)))
            .flatMap(after -> {
                log.warn("The water valve reported nothing that answers the command {} by {} within {}: unconfirmed "
                    + "({} in a row)", command.state(), command.source(), ValveCommand.WINDOW, after.inARow());
                return Mono.just(after)
                    .filter(run -> run.inARow() >= ALERT_IN_A_ROW && !run.announced())
                    .flatMap(run -> alert(command, run.inARow()));
            });
    }

    //not announced when the alert fails: the next unconfirmed command tries again
    private Mono<Void> alert(final ValveCommand command, final int inARow) {
        return Mono.fromSupplier(() -> valveTracker.current().freshOnly(now(), valveProperties.staleAfter()))
            .filter(fresh -> fresh.newestReport().isPresent())
            .switchIfEmpty(Mono.fromRunnable(() -> log.info("The water valve left {} commands in a row unconfirmed and "
                + "is not heard: the offline alarm tells of it", inARow)))
            .flatMap(fresh -> {
                final String text = alertText(command, inARow);
                return notificationPublisher.publishAlert(text)
                    .timeout(NOTICE_TIMEOUT)
                    .then(Mono.fromRunnable(() -> {
                        valveCommands.announced();
                        log.info("Water valve notification published: {}", text);
                    }))
                    .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                        "The water valve notification was not published, the next unconfirmed command tries again - "
                            + "{}: {}", failure.toString(), text)));
            })
            .then();
    }

    //"The water valve has not confirmed 3 commands in a row: the last one, close by dashboard at 10:05, reported
    //nothing within 60 s."
    static String alertText(final ValveCommand command, final int inARow) {
        return "The water valve has not confirmed " + inARow + " commands in a row: the last one, " + command.state()
            + " by " + command.source() + " at " + HOUR.format(command.at()) + ", reported nothing within "
            + ValveCommand.WINDOW.toSeconds() + " s.";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
