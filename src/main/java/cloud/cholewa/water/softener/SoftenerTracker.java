package cloud.cholewa.water.softener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

//The softener in memory (HAS-397): the last reading and when it was taken, and how the reads go. reachable is null until
//the first pass ended, false after MISSED_LIMIT passes in a row brought nothing - or at once when the account has no
//softener - true otherwise. A failed read keeps the last reading; GET /softener says how old it is. Read by the history,
//the alerts and the live flow of the epic (HAS-398..401). One instance - the Deployment is Recreate.
//The regeneration (HAS-399) is held apart from the reading, for the flow alarms of the valve; every start and end is logged
//with the value of regen_status_enum - which value a regeneration has is not seen yet
@Slf4j
@Component
public class SoftenerTracker {

    static final int MISSED_LIMIT = 2;

    private final AtomicReference<State> state = new AtomicReference<>(State.START);
    private final AtomicReference<Regeneration> regeneration = new AtomicReference<>(Regeneration.NONE);

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

    public Regeneration regeneration() {
        return regeneration.get();
    }

    //status - regen_status_enum, null when the reading has none; changedAt - its updated_at in the time of the house;
    //daysSince - days_since_last_regen. A status not 0 is a regeneration only on the day of one (daysSince 0 - the cloud sets
    //it at the start, 02:01:52 on 2026-11-09; null when not told): a value that means something else - queued for the night,
    //say - would otherwise hold the flow alarms of the evening for hours
    void regeneration(final Integer status, final Integer daysSince, final LocalDateTime changedAt,
                      final LocalTime scheduledAt, final LocalDateTime at) {
        final Boolean regenerating = status == null ? null : status != 0 && (daysSince == null || daysSince == 0);
        final Regeneration before = regeneration.get();
        final Regeneration after = regeneration.updateAndGet(held -> held.next(regenerating, changedAt, scheduledAt, at));
        if (after.regenerating() && !before.regenerating()) {
            log.info("The softener regenerates since {} (regen_status_enum {})", after.startedAt(), status);
        } else if (!after.regenerating() && before.regenerating()) {
            log.info("The regeneration of the softener ended at {} (regen_status_enum {})", after.endedAt(), status);
        }
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
