package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.softener.SoftenerReading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//The words of the alarms of the water softener (HAS-399), English and in the words of the alarms of the valve
//(AlarmMessages). An episode of the softener may last days - the salt above all - so a moment of another day than now is
//told with its date, and a length of a day or more in days and hours. Pinned by SoftenerAlarmRulesTest
final class SoftenerMessages {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final long MINUTES_A_DAY = Duration.ofDays(1).toMinutes();
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    private SoftenerMessages() {
    }

    //"Low salt in the water softener: 10 % left, about 5 days to run out (since 10:05)."
    //"The water softener reports a leak (since 10:05)."
    //"The water softener is not heard from the iQua cloud: last heard at 10:05 (35 min ago)."
    static String alert(final SoftenerAlarm alarm, final WaterAlertEntity episode, final LocalDateTime now,
                        final SoftenerReading reading) {
        if (alarm == SoftenerAlarm.OFFLINE) {
            return alarm.title() + ": last heard at " + moment(episode.openedAt(), now) + " ("
                + length(episode.openedAt(), now) + " ago).";
        }
        return alarm.title() + salt(alarm, reading) + " (since " + moment(episode.openedAt(), now) + ").";
    }

    //"Still low salt in the water softener: 10 % left, about 5 days to run out - since 8 Nov 10:05 (1 d 2 h)."
    //"Still: the water softener reports a leak - since 10:05 (1 h 5 min)."
    static String still(final SoftenerAlarm alarm, final WaterAlertEntity episode, final LocalDateTime now,
                        final SoftenerReading reading) {
        final String since = " - since " + moment(episode.openedAt(), now) + " (" + length(episode.openedAt(), now) + ").";
        if (alarm == SoftenerAlarm.LOW_SALT) {
            return "Still low salt in the water softener" + salt(alarm, reading) + since;
        }
        return "Still: " + lowerFirst(alarm.title()) + since;
    }

    //"The salt of the water softener refilled: 80 % - low from 8 Nov 10:05 to 10:20 (1 d 0 h)."
    //"Over: the water softener reports a leak no more - from 10:05 to 10:20 (15 min)."
    //"The water softener is heard from the iQua cloud again - not heard from 10:05 to 10:40 (35 min), the alert did not get
    //through."
    static String over(final SoftenerAlarm alarm, final WaterAlertEntity episode, final SoftenerReading reading) {
        final String span = " from " + moment(episode.openedAt(), episode.closedAt()) + " to " + HOUR.format(episode.closedAt())
            + " (" + length(episode.openedAt(), episode.closedAt()) + ")" + notTold(episode) + ".";
        return switch (alarm) {
            case LOW_SALT -> "The salt of the water softener refilled" + percent(reading).map(value -> ": " + value)
                .orElse("") + " - low" + span;
            case OFFLINE -> "The water softener is heard from the iQua cloud again - not heard" + span;
            default -> "Over: " + lowerFirst(alarm.title()) + " no more -" + span;
        };
    }

    //the salt left and the days the softener estimates, as far as the reading has them
    private static String salt(final SoftenerAlarm alarm, final SoftenerReading reading) {
        if (alarm != SoftenerAlarm.LOW_SALT || reading == null) {
            return "";
        }
        final String words = Stream.concat(
                percent(reading).map(value -> value + " left").stream(),
                Optional.ofNullable(reading.saltDaysLeft())
                    .map(value -> "about " + value + (value == 1 ? " day" : " days") + " to run out").stream())
            .collect(Collectors.joining(", "));
        return words.isEmpty() ? "" : ": " + words;
    }

    private static Optional<String> percent(final SoftenerReading reading) {
        return Optional.ofNullable(reading)
            .map(SoftenerReading::saltLevelPercent)
            .map(value -> BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                + " %");
    }

    private static String notTold(final WaterAlertEntity episode) {
        return episode.lastToldAt() == null ? ", the alert did not get through" : "";
    }

    private static String lowerFirst(final String text) {
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    //the hour when it is the day of now, the date and the hour otherwise
    static String moment(final LocalDateTime time, final LocalDateTime now) {
        return time.toLocalDate().equals(now.toLocalDate()) ? HOUR.format(time) : DAY_HOUR.format(time);
    }

    //in the zone of the house: "25 min", "1 h 5 min" as AlarmMessages, a day or more "2 d 3 h"
    static String length(final LocalDateTime from, final LocalDateTime to) {
        final ZoneId house = ZoneId.systemDefault();
        final long minutes = Math.max(0, Duration.between(from.atZone(house), to.atZone(house)).toMinutes());
        return minutes >= MINUTES_A_DAY
            ? (minutes / MINUTES_A_DAY) + " d " + (minutes % MINUTES_A_DAY) / 60 + " h"
            : AlarmMessages.length(from, to);
    }
}
