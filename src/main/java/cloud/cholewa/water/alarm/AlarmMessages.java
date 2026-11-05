package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

//The words of the alarms of the water valve (HAS-319), English as every alert of the services and in the words of the
//others (energy-service HAS-332, light-service HAS-264); the look is the owner's call, made on a real message
final class AlarmMessages {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final int KPA_PER_BAR = 2;

    private AlarmMessages() {
    }

    //"No mains water: the pressure at the water valve is 0.4 bar since 10:05 (6 min), below 1 bar."
    //"Frost risk: the water at the valve is 2.5 °C since 10:05 (6 min), below 3 °C."
    //"Not available: the water valve has not been heard since 10:05 (6 min)."
    static String alert(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now, final Double value,
                        final AlarmProperties properties) {
        return switch (alarm) {
            case NO_MAINS -> "No mains water: " + state(alarm, episode, now, value, properties) + ".";
            case FROST -> "Frost risk: " + state(alarm, episode, now, value, properties) + ".";
            case OFFLINE -> "Not available: " + state(alarm, episode, now, value, properties) + ".";
            default -> throw unworded(alarm);
        };
    }

    static String still(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now, final Double value,
                        final AlarmProperties properties) {
        return switch (alarm) {
            case NO_MAINS -> "Still no mains water: " + state(alarm, episode, now, value, properties) + ".";
            case FROST -> "Still a frost risk: " + state(alarm, episode, now, value, properties) + ".";
            case OFFLINE -> "Still not available: " + state(alarm, episode, now, value, properties) + ".";
            default -> throw unworded(alarm);
        };
    }

    //"Mains water back: 4.9 bar - below 1 bar from 10:05 to 10:20 (15 min)."
    //"Frost risk over: 4.1 °C - below 3 °C from 02:10 to 07:40 (5 h 30 min)."
    //"Available again: the water valve - out from 10:05 to 10:20 (15 min), the alert did not get through."
    static String over(final Alarm alarm, final WaterAlertEntity episode, final Double value,
                       final AlarmProperties properties) {
        final String span = " from " + hour(episode.openedAt()) + " to " + hour(episode.closedAt()) + " ("
            + length(episode.openedAt(), episode.closedAt()) + ")" + notTold(episode) + ".";
        return switch (alarm) {
            case NO_MAINS -> "Mains water back: " + bar(value) + " - below " + bar(properties.pressureLowBelow()) + span;
            case FROST -> "Frost risk over: " + celsius(value) + " - below " + celsius(properties.frostBelow()) + span;
            case OFFLINE -> "Available again: the water valve - out" + span;
            default -> throw unworded(alarm);
        };
    }

    private static String state(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now,
                                final Double value, final AlarmProperties properties) {
        final String since = " since " + hour(episode.openedAt()) + " (" + length(episode.openedAt(), now) + ")";
        return switch (alarm) {
            case NO_MAINS -> "the pressure at the water valve is " + bar(value) + since + ", below "
                + bar(properties.pressureLowBelow());
            case FROST -> "the water at the valve is " + celsius(value) + since + ", below "
                + celsius(properties.frostBelow());
            case OFFLINE -> "the water valve has not been heard" + since;
            default -> throw unworded(alarm);
        };
    }

    //the flow alarms (HAS-320) are worded by FlowMessages
    private static IllegalArgumentException unworded(final Alarm alarm) {
        return new IllegalArgumentException("The " + alarm.wireName() + " alarm is worded by FlowMessages");
    }

    private static String notTold(final WaterAlertEntity episode) {
        return episode.lastToldAt() == null ? ", the alert did not get through" : "";
    }

    //kPa of the valve as bar: "1 bar", "0.4 bar"
    private static String bar(final Double kilopascals) {
        return BigDecimal.valueOf(kilopascals).movePointLeft(KPA_PER_BAR).setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + " bar";
    }

    private static String celsius(final Double degrees) {
        return BigDecimal.valueOf(degrees).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " °C";
    }

    static String hour(final LocalDateTime time) {
        return HOUR.format(time);
    }

    //measured in the zone of the house, so that an episode across the change of the clocks has its real length:
    //"1 h 5 min", "25 min"
    static String length(final LocalDateTime from, final LocalDateTime to) {
        final ZoneId house = ZoneId.systemDefault();
        final long minutes = Math.max(0, Duration.between(from.atZone(house), to.atZone(house)).toMinutes());
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
}
