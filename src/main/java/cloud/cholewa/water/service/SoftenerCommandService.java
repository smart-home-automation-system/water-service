package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.SoftenerCommandReply;
import cloud.cholewa.water.softener.IquaClient;
import cloud.cholewa.water.softener.IquaException;
import cloud.cholewa.water.softener.SoftenerReader;
import cloud.cholewa.water.softener.SoftenerTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The commands of the regeneration (HAS-400) - the only write of the house on the softener: a regeneration now, one tonight
//(at the time of the softener, 02:00) and the cancel of one scheduled, sent once to the iQua cloud as the pairs the device
//offers in the actions of its detail (iqua/detail-scheduled.json). Refused before anything is sent: 503
//SOFTENER_UNAVAILABLE without a reading, 409 REGENERATION_IN_PROGRESS while the softener regenerates (the app offers no
//cancel of a running one), 409 COMMAND_TOO_SOON within MIN_GAP of the last command - one a minute at most, the rule of the
//charge of energy-service. A command the cloud refused or did not answer is 502 SOFTENER_COMMAND_FAILED with its status
//alone, and counts for the minute: it may have been carried out. The command is confirmed by the regeneration events of
//the cloud (HAS-398) - no row of its own
@Slf4j
@Service
public class SoftenerCommandService {

    static final Duration MIN_GAP = Duration.ofMinutes(1);
    private static final String FUNCTION = "regenerate";

    private final SoftenerReader softenerReader;
    private final SoftenerTracker softenerTracker;
    private final IquaClient iquaClient;
    private final Clock clock;
    private final AtomicReference<Instant> lastSent = new AtomicReference<>();

    public SoftenerCommandService(
        final SoftenerReader softenerReader,
        final SoftenerTracker softenerTracker,
        final IquaClient iquaClient,
        final Clock clock
    ) {
        this.softenerReader = softenerReader;
        this.softenerTracker = softenerTracker;
        this.iquaClient = iquaClient;
        this.clock = clock;
    }

    //word - what the house calls it in the answer; action - the action of the cloud within the function regenerate
    enum Command {
        NOW("regenerate now", FUNCTION),
        TONIGHT("regenerate tonight", "schedule"),
        CANCEL("cancel the scheduled regeneration", "cancel");

        private final String word;
        private final String action;

        Command(final String word, final String action) {
            this.word = word;
            this.action = action;
        }

        String action() {
            return action;
        }
    }

    //when - now (left out) or tonight
    public Mono<SoftenerCommandReply> regenerate(final String when) {
        return Mono.defer(() -> {
            final String chosen = Optional.ofNullable(when).map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .orElse("now");
            return Arrays.stream(new Command[]{Command.NOW, Command.TONIGHT})
                .filter(command -> command.name().toLowerCase(Locale.ROOT).equals(chosen))
                .findFirst()
                .map(this::send)
                .orElseGet(() -> Mono.error(ValveException.invalidSoftenerCommand(when)));
        });
    }

    public Mono<SoftenerCommandReply> cancel() {
        return Mono.defer(() -> send(Command.CANCEL));
    }

    private Mono<SoftenerCommandReply> send(final Command command) {
        final Optional<String> deviceId = softenerReader.deviceId();
        if (deviceId.isEmpty() || softenerTracker.current().reading() == null) {
            return Mono.error(ValveException.softenerUnavailable());
        }
        if (softenerTracker.regeneration().regenerating()) {
            return Mono.error(ValveException.regenerationInProgress());
        }
        final Instant now = clock.instant();
        final Instant last = lastSent.get();
        if (last != null && last.plus(MIN_GAP).isAfter(now) || !lastSent.compareAndSet(last, now)) {
            final Instant previous = Optional.ofNullable(lastSent.get()).orElse(now);
            return Mono.error(ValveException.commandTooSoon(Duration.between(previous, now).toSeconds()));
        }
        final LocalDateTime sentAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return iquaClient.command(deviceId.get(), FUNCTION, command.action())
            .map(result -> new SoftenerCommandReply(command.word, result.status(), result.message(), sentAt))
            .doOnNext(reply -> log.info("The softener was told to {}: {} {}", command.word, reply.status(), reply.message()))
            .onErrorMap(IquaException.class, failure -> {
                log.warn("The command to {} was not taken by the iQua cloud: {}", command.word, failure.getMessage());
                return ValveException.softenerCommandFailed(failure.getStatus());
            });
    }
}
