package cloud.cholewa.water.command;

import cloud.cholewa.water.database.repository.WaterValveCommandRepository;
import cloud.cholewa.water.valve.ValveListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The commands of the water valve in memory (HAS-321; the pod is Recreate, never scaled): told of every report of the
//state of the valve as it arrives (MQTT or the read of every minute), so the command it answers is confirmed at once -
//lastConfirmed of GET /valve, and confirmed_at of its row, stored in the background (a failure is a WARN, the
//confirmation in memory holds). The window of a command is judged by ValveCommandService
@Slf4j
@Component
@RequiredArgsConstructor
public class ValveCommands implements ValveListener {

    static final Duration STORE_TIMEOUT = Duration.ofSeconds(10);

    private final WaterValveCommandRepository repository;
    private final AtomicReference<CommandWatch> latest = new AtomicReference<>(CommandWatch.NONE);

    CommandWatch current() {
        return latest.get();
    }

    //null until a command was sent since the start, false while it waits and when nothing answered it, true once a
    //report did
    public Boolean lastConfirmed() {
        return latest.get().lastConfirmed();
    }

    public void issued(final ValveCommand command) {
        latest.updateAndGet(watch -> watch.issued(command));
    }

    //the command still pending at the end of its window: the watch after it, nothing for one confirmed or replaced
    Optional<CommandWatch> expired(final ValveCommand command) {
        while (true) {
            final CommandWatch before = latest.get();
            final Optional<CommandWatch> after = before.expired(command);
            if (after.isEmpty() || latest.compareAndSet(before, after.get())) {
                return after;
            }
        }
    }

    void announced() {
        latest.updateAndGet(CommandWatch::withAnnounced);
    }

    @Override
    public void state(final boolean open, final LocalDateTime at) {
        while (true) {
            final CommandWatch before = latest.get();
            final Optional<CommandWatch> after = before.reported(open, at);
            if (after.isEmpty()) {
                return;
            }
            if (latest.compareAndSet(before, after.get())) {
                confirmed(before.pending(), at);
                return;
            }
        }
    }

    private void confirmed(final ValveCommand command, final LocalDateTime at) {
        log.info("The water valve confirmed the command {} by {} of {}: {} at {}", command.state(), command.source(),
            command.at(), command.open() ? "open" : "closed", at);
        Optional.ofNullable(command.rowId()).ifPresent(id -> repository.confirm(id, at)
            .timeout(STORE_TIMEOUT)
            .contextCapture()
            .subscribe(null, failure -> log.warn("The confirmation of the command {} of the water valve could not be "
                + "stored: {}", id, failure.toString())));
    }
}
