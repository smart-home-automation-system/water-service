package cloud.cholewa.water.valve;

import java.time.Duration;
import java.time.LocalDateTime;

//One value of the valve and when it was last reported - by MQTT or by the read of every minute (HAS-316). The value
//may be null: the device reported the component without a reading. "at" is the time of the report on the clock of
//the house, not the time the value last changed on the device
public record Reported<T>(T value, LocalDateTime at) {

    //reported within staleAfter of now and with a value - what GET /valve shows and the snapshot stores
    boolean isFresh(final LocalDateTime now, final Duration staleAfter) {
        return value != null && !at.isBefore(now.minus(staleAfter));
    }
}
