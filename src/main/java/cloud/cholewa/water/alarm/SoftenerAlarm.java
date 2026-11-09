package cloud.cholewa.water.alarm;

import java.util.Arrays;
import java.util.Optional;

//The alarms of the water softener (HAS-399); the wire name is the kind of a row of water_alert. error - published at the
//level error (an alert), otherwise warn (the salt lasts days, the softener drops off Wi-Fi for minutes on its own);
//daily - reminded once a day at most, otherwise every water.alarm.reminder-every
public enum SoftenerAlarm {

    LOW_SALT("low salt", "Low salt in the water softener", false, true),
    LEAK("softener leak", "The water softener reports a leak", true, false),
    EXCESSIVE_USE("excessive use", "The water softener reports an excessive use of water", false, false),
    SHUTOFF_VALVE("softener valve", "The water softener reports an alarm of its shutoff valve", true, false),
    RESIN("softener resin", "The water softener reports an alarm of its resin", true, false),
    ERROR("softener error", "The water softener reports an error", true, false),
    OFFLINE("softener offline", "The water softener is not heard from the iQua cloud", false, false);

    private final String wireName;
    private final String title;
    private final boolean error;
    private final boolean daily;

    SoftenerAlarm(final String wireName, final String title, final boolean error, final boolean daily) {
        this.wireName = wireName;
        this.title = title;
        this.error = error;
        this.daily = daily;
    }

    public String wireName() {
        return wireName;
    }

    String title() {
        return title;
    }

    boolean error() {
        return error;
    }

    boolean daily() {
        return daily;
    }

    static Optional<SoftenerAlarm> of(final String wireName) {
        return Arrays.stream(values()).filter(alarm -> alarm.wireName.equals(wireName)).findFirst();
    }
}
