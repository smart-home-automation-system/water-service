package cloud.cholewa.water.alarm;

import cloud.cholewa.water.valve.FlowListener;
import cloud.cholewa.water.valve.ValveProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

//The flows of the valve, in memory (HAS-320): a run above the bound of each flow alarm - the high flow above
//water.flow.high-above, the continuous flow above zero, the flow of the empty house above water.flow.empty-house-above -
//from every report of the flow rate as it arrives (MQTT at every change of number:200, the read of every minute), and
//from the litres of the last minute of the counter the monitor hands in while the rate is not fresh. One
//AtomicReference: the listener of the broker, the read and the pass write it, the pass reads a whole state.
//Reports further apart than water.valve.stale-after and the minute of the pass are not one flow - the valve was not
//heard between them (a rate published on a change only is fresh for stale-after, then the pass hands in the counter);
//such a silence and the last will stop every run at the last report, so a flow before a silence and one after it are
//never added up, and an episode of the flow before closes where it was last heard
@Component
public class FlowTrack implements FlowListener {

    private static final Duration PASS = Duration.ofMinutes(1);

    private final FlowProperties flowProperties;
    private final ValveProperties valveProperties;
    private final AtomicReference<Flows> latest = new AtomicReference<>(Flows.NONE);

    public FlowTrack(final FlowProperties flowProperties, final ValveProperties valveProperties) {
        this.flowProperties = flowProperties;
        this.valveProperties = valveProperties;
    }

    //the runs of the three flow alarms, and the newest report: litres l/min at reportedAt; nulls before the first
    record Flows(Map<Alarm, FlowRun> runs, Double litres, LocalDateTime reportedAt) {

        static final Flows NONE = new Flows(Map.of(), null, null);

        Flows {
            runs = Map.copyOf(runs);
        }

        FlowRun run(final Alarm alarm) {
            return runs.get(alarm);
        }
    }

    Flows current() {
        return latest.get();
    }

    @Override
    public void flow(final double litresPerMinute, final LocalDateTime at) {
        latest.updateAndGet(flows -> next(flows, litresPerMinute, at));
    }

    @Override
    public void forget() {
        latest.updateAndGet(FlowTrack::stopped);
    }

    //what was flowing stopped at the last report - all that is known of it; an open episode then closes there
    private static Flows stopped(final Flows flows) {
        if (flows.reportedAt() == null) {
            return Flows.NONE;
        }
        final Map<Alarm, FlowRun> runs = new EnumMap<>(Alarm.class);
        flows.runs().forEach((alarm, run) -> runs.put(alarm, run.stoppedAt(flows.reportedAt())));
        return new Flows(runs, null, flows.reportedAt());
    }

    private Flows next(final Flows flows, final double litres, final LocalDateTime at) {
        if (flows.reportedAt() != null && at.isBefore(flows.reportedAt())) {
            //a read and a message crossed: the one that arrived later already holds
            return flows;
        }
        final Flows before = flows.reportedAt() != null
            && flows.reportedAt().plus(valveProperties.staleAfter()).plus(PASS).isBefore(at) ? stopped(flows) : flows;
        final Map<Alarm, FlowRun> runs = new EnumMap<>(Alarm.class);
        put(runs, Alarm.HIGH_FLOW, FlowRun.report(before.run(Alarm.HIGH_FLOW), litres, at, flowProperties.highAbove(),
            flowProperties.continuousPause()));
        put(runs, Alarm.CONTINUOUS_FLOW, FlowRun.report(before.run(Alarm.CONTINUOUS_FLOW), litres, at, 0,
            flowProperties.continuousPause()));
        put(runs, Alarm.EMPTY_HOUSE_FLOW, FlowRun.report(before.run(Alarm.EMPTY_HOUSE_FLOW), litres, at,
            flowProperties.emptyHouseAbove(), flowProperties.continuousPause()));
        return new Flows(runs, litres, at);
    }

    private static void put(final Map<Alarm, FlowRun> runs, final Alarm alarm, final FlowRun run) {
        if (run != null) {
            runs.put(alarm, run);
        }
    }
}
