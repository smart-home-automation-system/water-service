package cloud.cholewa.water.valve;

import cloud.cholewa.shelly.model.BooleanStatus;
import cloud.cholewa.shelly.model.NumberStatus;
import cloud.cholewa.shelly.model.ObjectStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

//The state of the water valve, in memory (HAS-316; the pod is Recreate, never scaled). Written by the MQTT listener and
//by the read of every minute, read by the API and by the snapshot - one AtomicReference, so a reader always sees a whole
//state. A message and a read are the valve as it was at their arrival, so the later one wins. Which component is which
//value is the key of the virtual component of the Neo Smart Water Valve (Shelly X XT1, firmware 2.0.1): boolean:200 the
//valve, number:200 the flow rate, number:201 the pressure, number:202 the water temperature, object:200 the counter.
//Every flow rate (HAS-320, in l/min) and every state of the valve (HAS-321) is also handed to the listeners as it arrives
@Slf4j
@Component
public class ValveTracker {

    static final int STATE = 200;
    static final int FLOW_RATE = 200;
    static final int PRESSURE = 201;
    static final int TEMPERATURE = 202;
    static final int COUNTER = 200;

    //the components of the valve that are read, by their type, and the ids the house reads of each
    static final Map<String, Set<Integer>> READ = Map.of(
        "boolean", Set.of(STATE),
        "number", Set.of(FLOW_RATE, PRESSURE, TEMPERATURE),
        "object", Set.of(COUNTER));

    private static final int LITRES_PER_CUBIC_METRE = 3;

    private final Clock clock;
    private final List<ValveListener> listeners;
    private final AtomicReference<ValveState> latest = new AtomicReference<>(ValveState.UNKNOWN);

    @Autowired
    public ValveTracker(final Clock clock, final List<ValveListener> listeners) {
        this.clock = clock;
        this.listeners = List.copyOf(listeners);
    }

    public ValveTracker(final Clock clock, final ValveListener... listeners) {
        this(clock, List.of(listeners));
    }

    public ValveState current() {
        return latest.get();
    }

    static boolean isRead(final String component, final Integer index) {
        return component != null && index != null && READ.getOrDefault(component, Set.of()).contains(index);
    }

    //the status of one component; one the house does not read, or of a type it does not know, changes nothing
    public void report(final int index, final Object status) {
        final LocalDateTime now = now();
        latest.updateAndGet(current -> switch (status) {
            case BooleanStatus value when index == STATE -> current.withOpen(new Reported<>(value.getValue(), now));
            case NumberStatus value when index == FLOW_RATE -> current.withFlowRate(new Reported<>(value.getValue(), now));
            case NumberStatus value when index == PRESSURE -> current.withPressure(new Reported<>(value.getValue(), now));
            case NumberStatus value when index == TEMPERATURE ->
                current.withTemperature(new Reported<>(value.getValue(), now));
            case ObjectStatus value when index == COUNTER ->
                current.withCounter(new Reported<>(totalOf(value), now), new Reported<>(lastMinuteOf(value), now));
            default -> current;
        });
        if (status instanceof NumberStatus rate && index == FLOW_RATE && rate.getValue() != null) {
            listeners.forEach(listener -> listener.flow(litres(rate.getValue()), now));
        }
        if (status instanceof BooleanStatus valve && index == STATE && valve.getValue() != null) {
            listeners.forEach(listener -> listener.state(valve.getValue(), now));
        }
    }

    //the last will (false) and the return (true) of the valve on the broker; logged when it changes - it is rare, and
    //it is what tells a silent valve from a quiet broker. The last will forgets the values: nothing is known of a valve
    //that left, and GET /valve would serve them as fresh for minutes - the read of every minute brings them back while
    //the valve still answers its RPC
    void online(final boolean online) {
        final ValveState before = latest.getAndUpdate(current -> online
            ? current.withOnline(true)
            : ValveState.UNKNOWN.withOnline(false));
        if (!online) {
            listeners.forEach(ValveListener::forget);
        }
        if (!Boolean.valueOf(online).equals(before.online())) {
            log.info("The water valve is {} the broker", online ? "back on" : "off");
        }
    }

    //another valve in the registry: what was known is of the one before
    public void reset() {
        latest.set(ValveState.UNKNOWN);
        listeners.forEach(ValveListener::forget);
    }

    //value.counter.total of the counter, in m3; null when the device left it out. A whole number arrives as an Integer
    private static Double totalOf(final ObjectStatus status) {
        return counterOf(status) instanceof Map<?, ?> counter && counter.get("total") instanceof Number total
            ? total.doubleValue() : null;
    }

    //the first of value.counter.by_minute - the litres of the last whole minute, the newest first; null when left out
    private static Double lastMinuteOf(final ObjectStatus status) {
        return counterOf(status) instanceof Map<?, ?> counter && counter.get("by_minute") instanceof List<?> minutes
            && !minutes.isEmpty() && minutes.getFirst() instanceof Number litres ? litres.doubleValue() : null;
    }

    private static Object counterOf(final ObjectStatus status) {
        return status.getValue() == null ? null : status.getValue().get("counter");
    }

    //m3/min of the device as l/min - the unit of the house and of the flow alarms
    public static double litres(final double cubicMetres) {
        return BigDecimal.valueOf(cubicMetres).movePointRight(LITRES_PER_CUBIC_METRE).doubleValue();
    }

    //cut to what a column of the database keeps, so the API and the database tell the same time
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
