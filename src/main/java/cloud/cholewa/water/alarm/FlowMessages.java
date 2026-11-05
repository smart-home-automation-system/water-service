package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import static cloud.cholewa.water.alarm.AlarmMessages.hour;
import static cloud.cholewa.water.alarm.AlarmMessages.length;

//The words of the flow alarms of the water valve (HAS-320), English as every alert of the services, in the shape of
//AlarmMessages; the look is the owner's call, made on a real message. Flows in l/min
final class FlowMessages {

    private FlowMessages() {
    }

    //"High water flow: 32.5 l/min since 10:05 (4 min), above 25 l/min."
    //"Continuous water flow: running without a pause since 10:05 (30 min), 2.1 l/min now."
    //"Water running while nobody is at home: 3.2 l/min since 10:05 (2 min), the house empty since 09:40."
    static String alert(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now, final Double litres,
                        final House house, final FlowProperties properties) {
        return switch (alarm) {
            case HIGH_FLOW -> "High water flow: " + state(alarm, episode, now, litres, house, properties) + ".";
            case CONTINUOUS_FLOW -> "Continuous water flow: " + state(alarm, episode, now, litres, house, properties) + ".";
            case EMPTY_HOUSE_FLOW -> "Water running while nobody is at home: "
                + state(alarm, episode, now, litres, house, properties) + ".";
            default -> throw unworded(alarm);
        };
    }

    static String still(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now, final Double litres,
                        final House house, final FlowProperties properties) {
        return switch (alarm) {
            case HIGH_FLOW -> "Still a high water flow: " + state(alarm, episode, now, litres, house, properties) + ".";
            case CONTINUOUS_FLOW -> "Still a continuous water flow: " + state(alarm, episode, now, litres, house, properties)
                + ".";
            case EMPTY_HOUSE_FLOW -> "Still water running while nobody is at home: "
                + state(alarm, episode, now, litres, house, properties) + ".";
            default -> throw unworded(alarm);
        };
    }

    //"High water flow over: above 25 l/min from 10:05 to 10:20 (15 min), at most 40 l/min."
    //"Continuous water flow over: it ran from 10:05 to 11:40 (1 h 35 min), at most 12 l/min."
    //"Water stopped in the empty house: it ran from 10:05 to 10:07 (2 min), at most 6 l/min."
    static String over(final Alarm alarm, final WaterAlertEntity episode, final Double peak,
                       final FlowProperties properties) {
        final String span = " from " + hour(episode.openedAt()) + " to " + hour(episode.closedAt()) + " ("
            + length(episode.openedAt(), episode.closedAt()) + ")" + (peak == null ? "" : ", at most " + litres(peak))
            + (episode.lastToldAt() == null ? ", the alert did not get through" : "") + ".";
        return switch (alarm) {
            case HIGH_FLOW -> "High water flow over: above " + litres(properties.highAbove()) + span;
            case CONTINUOUS_FLOW -> "Continuous water flow over: it ran" + span;
            case EMPTY_HOUSE_FLOW -> "Water stopped in the empty house: it ran" + span;
            default -> throw unworded(alarm);
        };
    }

    private static String state(final Alarm alarm, final WaterAlertEntity episode, final LocalDateTime now,
                                final Double litres, final House house, final FlowProperties properties) {
        final String since = " since " + hour(episode.openedAt()) + " (" + length(episode.openedAt(), now) + ")";
        final String flow = litres == null ? "the flow not known now" : litres(litres);
        return switch (alarm) {
            case HIGH_FLOW -> flow + since + ", above " + litres(properties.highAbove());
            case CONTINUOUS_FLOW -> "running without a pause" + since + ", " + (litres == null ? flow : flow + " now");
            case EMPTY_HOUSE_FLOW -> flow + since + (house.emptySince() == null ? ""
                : ", the house empty since " + hour(house.emptySince()));
            default -> throw unworded(alarm);
        };
    }

    //the alarms of the valve (HAS-319) are worded by AlarmMessages
    private static IllegalArgumentException unworded(final Alarm alarm) {
        return new IllegalArgumentException("The " + alarm.wireName() + " alarm is worded by AlarmMessages");
    }

    //"32.5 l/min", "25 l/min"
    static String litres(final double litresPerMinute) {
        return BigDecimal.valueOf(litresPerMinute).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            + " l/min";
    }
}
