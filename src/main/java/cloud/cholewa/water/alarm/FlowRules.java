package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.softener.Regeneration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

//What the monitor decides of the flow (HAS-320), without I/O - pinned by FlowRulesTest. Each of the three is judged by
//its own run of FlowTrack, so a high flow that is also continuous raises the high flow at its bound and the continuous
//one at its own; one episode of each at a time:
//- High flow: a run above water.flow.high-above lasting high-after.
//- Continuous flow: a run above zero lasting continuous-after - a pause no longer than continuous-pause is the same run.
//- The empty house: a run above water.flow.empty-house-above while the house is known to be empty for empty-house-after
//  (unknown is never empty - presence-service not read included); it opens at the start of the run.
//Each: an alert, a reminder every water.alarm.reminder-every while it lasts, an info when the flow stops - its run fallen
//for longer than the pause, or a new run after such a fall, closed where it fell. While the flow is not known (neither
//the rate nor the counter fresh) nothing opens or closes - the offline alarm speaks for a valve that left; reminders go
//on. An episode read after a restart goes on with the first run the track sees. As in AlarmRules, what a notice changes
//is held only once it was published.
//The regeneration of the softener (HAS-399) takes water from the mains, about 0.5 l/min for two hours: a continuous flow or
//one of the empty house that started with a regeneration is judged from where it ended - raised only when the water runs
//on after it (the continuous flow for continuous-after from the end, the empty house for longer than the pause); a flow
//that starts near the time of the regeneration waits for the cloud to say whether the softener regenerates
//(Regeneration.flowStart). The high flow is never excused
final class FlowRules {

    private static final List<Alarm> FLOW = List.of(Alarm.HIGH_FLOW, Alarm.CONTINUOUS_FLOW, Alarm.EMPTY_HOUSE_FLOW);

    private FlowRules() {
    }

    //known - the flow is known now (the rate or the counter fresh); litres - the flow now, l/min, null when not known;
    //regeneration - of the softener, Regeneration.NONE when nothing is known of it
    record Readings(FlowTrack.Flows flows, boolean known, Double litres, House house, Regeneration regeneration) {
    }

    static AlarmRules.Decision decide(final AlarmWatch held, final Readings readings, final LocalDateTime now,
                                      final FlowProperties flow, final AlarmProperties alarm) {
        final List<AlarmRules.Notice> notices = new ArrayList<>();
        AlarmWatch next = held;
        for (final Alarm each : FLOW) {
            next = judge(next, each, readings, now, flow, alarm, notices);
        }
        return new AlarmRules.Decision(next, List.copyOf(notices));
    }

    private static AlarmWatch judge(final AlarmWatch held, final Alarm alarm, final Readings readings,
                                    final LocalDateTime now, final FlowProperties flow, final AlarmProperties properties,
                                    final List<AlarmRules.Notice> notices) {
        final WaterAlertEntity open = held.open().get(alarm);
        final FlowRun run = readings.flows().run(alarm);
        if (open != null) {
            stopped(open, run, readings.known(), now, flow)
                .map(end -> {
                    final WaterAlertEntity closed = open.closed(end);
                    final Double peak = run != null && !run.start().isAfter(end) ? run.peak() : null;
                    return new AlarmRules.Notice(AlarmRules.Type.INFO, alarm,
                        FlowMessages.over(alarm, closed, peak, flow), closed);
                })
                .or(() -> due(alarm, open, readings, now, flow, properties))
                .ifPresent(notices::add);
            return held;
        }
        if (!readings.known() || run == null || !run.activeAt(now, flow.continuousPause()) || !opens(alarm, run,
            readings.house(), now, flow)) {
            return held;
        }
        final Regeneration.FlowStart start = alarm == Alarm.HIGH_FLOW
            ? new Regeneration.FlowStart(false, run.start())
            : readings.regeneration().flowStart(run.start(), now);
        if (start.held() || start.excused(run.start()) && !runsOn(alarm, run, start.start(), now, flow)) {
            return held;
        }
        final WaterAlertEntity opened = new WaterAlertEntity(null, alarm.wireName(), start.start(), null, null);
        notices.add(alert(alarm, opened, readings, now, flow));
        return held.with(alarm, opened);
    }

    private static boolean opens(final Alarm alarm, final FlowRun run, final House house, final LocalDateTime now,
                                 final FlowProperties flow) {
        //the high and the continuous flow only while the water still runs - a flow that just fell would be told and
        //over in two passes; the empty house also in the pause after a fall, so a flush between two passes is seen
        return switch (alarm) {
            case HIGH_FLOW -> run.above() && run.lastedAtLeast(flow.highAfter(), now);
            case CONTINUOUS_FLOW -> run.above() && run.lastedAtLeast(flow.continuousAfter(), now);
            case EMPTY_HOUSE_FLOW -> house.emptyFor(flow.emptyHouseAfter(), now);
            default -> throw new IllegalArgumentException("Not a flow alarm: " + alarm.wireName());
        };
    }

    //a flow the regeneration excused until from: the water ran on after it - above for continuous-after since then, or
    //for longer than the pause of the flow alarms in the empty house
    private static boolean runsOn(final Alarm alarm, final FlowRun run, final LocalDateTime from, final LocalDateTime now,
                                  final FlowProperties flow) {
        final Duration after = alarm == Alarm.CONTINUOUS_FLOW ? flow.continuousAfter() : flow.continuousPause();
        return run.above() && from.plus(after).isBefore(run.endAt(now));
    }

    //where the flow of an open episode stopped, or nothing while it goes on or is not known
    private static Optional<LocalDateTime> stopped(final WaterAlertEntity open, final FlowRun run, final boolean known,
                                                   final LocalDateTime now, final FlowProperties flow) {
        if (!known) {
            return Optional.empty();
        }
        if (run == null) {
            //nothing above the bound since the track knows the flow - after a restart or a silence
            return Optional.of(now);
        }
        if (run.previousEnd() != null && run.start().isAfter(open.openedAt())) {
            //a new run: the flow of the episode fell for longer than the pause between two passes
            return Optional.of(later(run.previousEnd(), open.openedAt()));
        }
        return run.activeAt(now, flow.continuousPause()) ? Optional.empty()
            : Optional.of(later(run.lastAbove(), open.openedAt()));
    }

    //the alert again while it has not got through, or the reminder when it is due
    private static Optional<AlarmRules.Notice> due(final Alarm alarm, final WaterAlertEntity open,
                                                   final Readings readings, final LocalDateTime now,
                                                   final FlowProperties flow, final AlarmProperties properties) {
        if (open.lastToldAt() == null) {
            return Optional.of(alert(alarm, open, readings, now, flow));
        }
        return open.lastToldAt().plus(properties.reminderEvery()).isAfter(now)
            ? Optional.empty()
            : Optional.of(new AlarmRules.Notice(AlarmRules.Type.REMINDER, alarm,
                FlowMessages.still(alarm, open, now, readings.litres(), readings.house(), flow), open.told(now)));
    }

    private static AlarmRules.Notice alert(final Alarm alarm, final WaterAlertEntity open, final Readings readings,
                                           final LocalDateTime now, final FlowProperties flow) {
        return new AlarmRules.Notice(AlarmRules.Type.ALERT, alarm,
            FlowMessages.alert(alarm, open, now, readings.litres(), readings.house(), flow), open.told(now));
    }

    private static LocalDateTime later(final LocalDateTime one, final LocalDateTime other) {
        return one.isAfter(other) ? one : other;
    }
}
