package cloud.cholewa.water.alarm;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

//One flow above a bound, as the reports of the flow rate tell it (HAS-320): from start, the first report above the
//bound, to lastAbove - the newest report above it while it is above, the report that fell to the bound or below once it
//is not. A fall shorter than the pause of the flow alarms is the same flow; a longer one ends it, and the next report
//above starts a new run. peak - the most of the run, l/min; previousEnd - where the run before it ended, null for the
//first. Without I/O - pinned by FlowTrackTest
record FlowRun(LocalDateTime start, LocalDateTime lastAbove, boolean above, double peak, LocalDateTime previousEnd) {

    FlowRun {
        Objects.requireNonNull(start);
        Objects.requireNonNull(lastAbove);
    }

    //a report of the flow, l/min, at its time; run is the run so far, null before the first one above the bound
    static FlowRun report(final FlowRun run, final double litres, final LocalDateTime at, final double bound,
                          final Duration pause) {
        if (litres > bound) {
            if (run == null) {
                return new FlowRun(at, at, true, litres, null);
            }
            if (!run.above && run.lastAbove.plus(pause).isBefore(at)) {
                return new FlowRun(at, at, true, litres, run.lastAbove);
            }
            return new FlowRun(run.start, at, true, Math.max(run.peak, litres), run.previousEnd);
        }
        return run != null && run.above ? new FlowRun(run.start, at, false, run.peak, run.previousEnd) : run;
    }

    //nothing more is known after at - the valve was last heard then: a run above falls there
    FlowRun stoppedAt(final LocalDateTime at) {
        return above ? new FlowRun(start, at, false, peak, previousEnd) : this;
    }

    //still flowing at now: above, or fallen for no longer than the pause
    boolean activeAt(final LocalDateTime now, final Duration pause) {
        return above || !lastAbove.plus(pause).isBefore(now);
    }

    //the end of the flow so far: now while above, the fall once not
    LocalDateTime endAt(final LocalDateTime now) {
        return above ? now : lastAbove;
    }

    boolean lastedAtLeast(final Duration length, final LocalDateTime now) {
        return !start.plus(length).isAfter(endAt(now));
    }
}
