package cloud.cholewa.water.circulation;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

//What is known of the pump (HAS-404), in memory - one instance, the Deployment is Recreate: the newest read of the Uni
//(a read asked before the one held is dropped - two reads may answer out of order), since when the relay is on, and the run
//the service started while it goes on. Every method is synchronized: the monitor
//(its own thread of the scheduler) and a start (a thread of the server) change it
@Component
class CirculationPump {

    private UniReading reading;
    private LocalDateTime onSince;
    private CirculationRun run;

    synchronized void read(final UniReading newer) {
        if (reading != null && newer.readAt().isBefore(reading.readAt())) {
            return;
        }
        reading = newer;
        if (Boolean.TRUE.equals(newer.pumpOn()) && onSince == null) {
            onSince = run != null ? run.startedAt() : newer.readAt();
        }
        if (Boolean.FALSE.equals(newer.pumpOn())) {
            onSince = null;
        }
    }

    synchronized Optional<UniReading> reading() {
        return Optional.ofNullable(reading);
    }

    synchronized Optional<LocalDateTime> onSince() {
        return Optional.ofNullable(onSince);
    }

    synchronized Optional<CirculationRun> run() {
        return Optional.ofNullable(run);
    }

    synchronized void started(final CirculationRun started) {
        run = started;
        onSince = started.startedAt();
    }

    //the row of the run is stored: its id, if it is still the run going on
    synchronized void stored(final CirculationRun stored) {
        if (sameRun(stored)) {
            run = stored;
        }
    }

    synchronized boolean isCurrent(final CirculationRun other) {
        return sameRun(other);
    }

    //false when that run had ended already - nothing to end twice
    synchronized boolean ended(final CirculationRun ending) {
        if (!sameRun(ending)) {
            return false;
        }
        run = null;
        return true;
    }

    private boolean sameRun(final CirculationRun other) {
        return run != null && Objects.equals(run.startedAt(), other.startedAt());
    }
}
