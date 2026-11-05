package cloud.cholewa.water.alarm;

import java.util.Arrays;
import java.util.Optional;

//The alarms of the water valve (HAS-319) and of its flow (HAS-320); the wire name is the kind of a row of water_alert
public enum Alarm {

    NO_MAINS("no mains"),
    OFFLINE("offline"),
    FROST("frost"),
    HIGH_FLOW("high flow"),
    CONTINUOUS_FLOW("continuous flow"),
    EMPTY_HOUSE_FLOW("empty house flow");

    private final String wireName;

    Alarm(final String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    static Optional<Alarm> of(final String wireName) {
        return Arrays.stream(values()).filter(alarm -> alarm.wireName.equals(wireName)).findFirst();
    }
}
