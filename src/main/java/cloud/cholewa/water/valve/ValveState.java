package cloud.cholewa.water.valve;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

//What is known of the water valve, in memory (HAS-316), in the units of the device: open is boolean:200, flowRate
//number:200 (m3/min), pressure number:201 (kPa), temperature number:202 (degrees C), counterTotal the total of
//object:200 (m3). A value never reported is null; online is the last word of its online topic, null before one
public record ValveState(
    Reported<Boolean> open,
    Reported<Double> flowRate,
    Reported<Double> pressure,
    Reported<Double> temperature,
    Reported<Double> counterTotal,
    Boolean online
) {

    static final ValveState UNKNOWN = new ValveState(null, null, null, null, null, null);

    ValveState withOpen(final Reported<Boolean> reported) {
        return new ValveState(reported, flowRate, pressure, temperature, counterTotal, online);
    }

    ValveState withFlowRate(final Reported<Double> reported) {
        return new ValveState(open, reported, pressure, temperature, counterTotal, online);
    }

    ValveState withPressure(final Reported<Double> reported) {
        return new ValveState(open, flowRate, reported, temperature, counterTotal, online);
    }

    ValveState withTemperature(final Reported<Double> reported) {
        return new ValveState(open, flowRate, pressure, reported, counterTotal, online);
    }

    ValveState withCounterTotal(final Reported<Double> reported) {
        return new ValveState(open, flowRate, pressure, temperature, reported, online);
    }

    ValveState withOnline(final boolean value) {
        return new ValveState(open, flowRate, pressure, temperature, counterTotal, value);
    }

    //the state with every value that is not fresh left out - what may be shown and stored
    public ValveState freshOnly(final LocalDateTime now, final Duration staleAfter) {
        return new ValveState(fresh(open, now, staleAfter), fresh(flowRate, now, staleAfter),
            fresh(pressure, now, staleAfter), fresh(temperature, now, staleAfter), fresh(counterTotal, now, staleAfter),
            online);
    }

    //the newest report of the values there are, or nothing when there is none
    public Optional<LocalDateTime> newestReport() {
        return Stream.of(open, flowRate, pressure, temperature, counterTotal)
            .filter(Objects::nonNull)
            .map(Reported::at)
            .max(LocalDateTime::compareTo);
    }

    boolean hasStatus() {
        return open != null || flowRate != null || pressure != null || temperature != null;
    }

    private static <T> Reported<T> fresh(final Reported<T> reported, final LocalDateTime now, final Duration staleAfter) {
        return reported != null && reported.isFresh(now, staleAfter) ? reported : null;
    }
}
