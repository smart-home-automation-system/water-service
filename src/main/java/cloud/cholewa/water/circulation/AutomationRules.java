package cloud.cholewa.water.circulation;

import cloud.cholewa.water.presence.HouseOccupancy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

//Whether an automatic run may start (HAS-405) - no I/O, pinned by AutomationRulesTest. Judged when a trigger came, the
//cheap conditions first (what is in memory), then the pause (the database), then the house (presence-service) - each
//answers why not, for the DEBUG log, or nothing when it holds. Unknown never starts: a Uni not read within STALE_AFTER, a
//tank or a return it did not report, a house presence-service does not know. The manual start obeys the tank alone
final class AutomationRules {

    private AutomationRules() {
    }

    //quiet hours, a run going on, the pump already on (the scene of the Shelly cloud), the Uni not read, the tank too cold,
    //the return warm enough
    static Optional<String> blockedNow(final LocalDateTime now, final Optional<UniReading> reading, final boolean runGoing,
                                       final AutomationProperties automation, final CirculationProperties circulation) {
        if (automation.quiet(now.toLocalTime())) {
            return Optional.of("the quiet hours");
        }
        if (runGoing) {
            return Optional.of("a run goes on");
        }
        final Optional<UniReading> fresh = reading.filter(read -> !read.readAt().isBefore(now.minus(CirculationService.STALE_AFTER)));
        if (fresh.isEmpty()) {
            return Optional.of("the Uni was not read for a minute");
        }
        final UniReading read = fresh.get();
        if (Boolean.TRUE.equals(read.pumpOn())) {
            return Optional.of("the pump is on");
        }
        if (read.tank() == null) {
            return Optional.of("the tank not reported");
        }
        if (read.tank() < circulation.tankMin()) {
            return Optional.of("the tank at " + read.tank() + " C");
        }
        if (read.returnTemperature() == null) {
            return Optional.of("the return not reported");
        }
        if (read.returnTemperature() >= automation.startBelow()) {
            return Optional.of("the return at " + read.returnTemperature() + " C");
        }
        return Optional.empty();
    }

    //lastSuccess - the end of the newest run stopped by the temperature or the limit, null for none
    static Optional<String> paused(final LocalDateTime now, final LocalDateTime lastSuccess, final Duration pauseAfter) {
        return Optional.ofNullable(lastSuccess)
            .filter(ended -> now.isBefore(ended.plus(pauseAfter)))
            .map(ended -> "the pause after the run that ended at " + ended.toLocalTime().withNano(0));
    }

    static Optional<String> nobodyHome(final HouseOccupancy house) {
        return house == HouseOccupancy.OCCUPIED ? Optional.empty() : Optional.of("the house is " + house.value());
    }
}
