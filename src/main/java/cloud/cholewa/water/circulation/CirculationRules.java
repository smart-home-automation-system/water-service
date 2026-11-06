package cloud.cholewa.water.circulation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * When a run of the pump ends (HAS-404) - no I/O, pinned by CirculationRulesTest. Judged at every read of the Uni while a
 * run goes on (every 5 s) and when a read fails:
 * <ul>
 *   <li>a read asked before the run started says nothing of it (it may be the read that saw the pump off a moment
 *   before);</li>
 *   <li>the relay found off ended the run: by the limit once the run lasted max-run (the timer of the device, give or
 *   take TIMER_SLACK), otherwise by the device - a hand in the Shelly app, a power cut, the scene of the Shelly cloud; a
 *   start the Uni did not answer found off is a failure - it may never have reached the pump;</li>
 *   <li>the relay on: the return at or above its target stops it (with stop-on-return), max-run stops it at the
 *   latest;</li>
 *   <li>the Uni not heard (or the relay not known) past max-run and UNHEARD_AFTER: the run is given up as a failure - the
 *   timer sent with the start stops the pump, and the run must not be held for ever.</li>
 * </ul>
 */
final class CirculationRules {

    static final Duration TIMER_SLACK = Duration.ofSeconds(10);
    static final Duration UNHEARD_AFTER = Duration.ofMinutes(1);

    static final String TEMPERATURE = "temperature";
    static final String LIMIT = "limit";
    static final String DEVICE = "device";
    static final String FAILURE = "failure";

    //how a run ends: reason - stopped_by of its row; switchOff - whether the relay still has to be switched off
    record Stop(String reason, boolean switchOff) {
    }

    private CirculationRules() {
    }

    static Optional<Stop> afterRead(final CirculationRun run, final UniReading reading,
                                    final CirculationProperties properties) {
        if (reading.readAt().isBefore(run.startedAt())) {
            return Optional.empty();
        }
        //the times are of the house (LocalDateTime.now(ZoneId.systemDefault())): a run across a change of the clock
        final ZoneId house = ZoneId.systemDefault();
        final Duration lasted = Duration.between(run.startedAt().atZone(house), reading.readAt().atZone(house));
        if (Boolean.FALSE.equals(reading.pumpOn())) {
            return Optional.of(new Stop(foundOff(run, lasted, properties), false));
        }
        if (Boolean.TRUE.equals(reading.pumpOn())) {
            return returnReached(reading, properties)
                ? Optional.of(new Stop(TEMPERATURE, true))
                : Optional.of(new Stop(LIMIT, true)).filter(stop -> lasted.compareTo(properties.maxRun()) >= 0);
        }
        return unheard(run, reading.readAt(), properties);
    }

    static Optional<Stop> unheard(final CirculationRun run, final LocalDateTime now,
                                  final CirculationProperties properties) {
        return Optional.of(new Stop(FAILURE, false))
            .filter(stop -> !now.isBefore(run.startedAt().plus(properties.maxRun()).plus(UNHEARD_AFTER)));
    }

    private static String foundOff(final CirculationRun run, final Duration lasted, final CirculationProperties properties) {
        if (!run.answered()) {
            return FAILURE;
        }
        return lasted.compareTo(properties.maxRun().minus(TIMER_SLACK)) >= 0 ? LIMIT : DEVICE;
    }

    //when a run given up as unheard ended: the timer of the device stopped the pump at max-run at the latest
    static LocalDateTime unheardEnd(final CirculationRun run, final LocalDateTime now,
                                    final CirculationProperties properties) {
        final LocalDateTime timer = run.startedAt().plus(properties.maxRun());
        return now.isBefore(timer) ? now : timer;
    }

    private static boolean returnReached(final UniReading reading, final CirculationProperties properties) {
        return properties.stopOnReturn()
            && reading.returnTemperature() != null
            && reading.returnTemperature() >= properties.returnTarget();
    }
}
