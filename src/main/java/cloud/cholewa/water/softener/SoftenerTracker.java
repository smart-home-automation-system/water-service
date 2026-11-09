package cloud.cholewa.water.softener;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

//The softener in memory (HAS-397): the last reading and when it was taken, and how the reads go. reachable is null until
//the first pass ended, false after MISSED_LIMIT passes in a row brought nothing - or at once when the account has no
//softener - true otherwise. A failed read keeps the last reading; GET /softener says how old it is. Read by the history,
//the alerts and the live flow of the epic (HAS-398..401). One instance - the Deployment is Recreate
@Component
public class SoftenerTracker {

    static final int MISSED_LIMIT = 2;

    private final AtomicReference<State> state = new AtomicReference<>(State.START);

    //reading, measuredAt (the clock of the house) and readAt (the instant, for the age of the reading - a house time goes
    //back an hour in October) - null until the first reading; missed - passes in a row without a reading
    public record State(SoftenerReading reading, LocalDateTime measuredAt, Instant readAt, boolean passed, int missed) {

        static final State START = new State(null, null, null, false, 0);

        public Boolean reachable() {
            return passed ? missed < MISSED_LIMIT : null;
        }
    }

    public State current() {
        return state.get();
    }

    void read(final SoftenerReading reading, final LocalDateTime at, final Instant instant) {
        state.set(new State(reading, at, instant, true, 0));
    }

    void missed() {
        state.updateAndGet(held -> new State(held.reading(), held.measuredAt(), held.readAt(), true, held.missed() + 1));
    }

    //the account has no softener the service may read: unreachable at once, the last reading kept
    void noSoftener() {
        state.updateAndGet(held -> new State(held.reading(), held.measuredAt(), held.readAt(), true,
            Math.max(held.missed() + 1, MISSED_LIMIT)));
    }
}
