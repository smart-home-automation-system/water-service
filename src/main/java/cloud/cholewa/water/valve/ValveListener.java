package cloud.cholewa.water.valve;

import java.time.LocalDateTime;

//Told of the reports of the valve as they arrive: the flow rate for the flow alarms (HAS-320) - a pause shorter than a
//minute, which the minute pass alone would not see - and the state of the valve for the confirmation of a command
//(HAS-321). Called on the thread of the report, so it must not block; each method does nothing unless overridden
public interface ValveListener {

    //the flow in l/min, at the time of the report on the clock of the house
    default void flow(final double litresPerMinute, final LocalDateTime at) {
        //not listened to
    }

    //the valve open (true) or closed, at the time of the report on the clock of the house
    default void state(final boolean open, final LocalDateTime at) {
        //not listened to
    }

    //nothing more is known of the valve: its last will, or another valve in the registry
    default void forget() {
        //not listened to
    }
}
