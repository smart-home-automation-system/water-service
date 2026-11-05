package cloud.cholewa.water.valve;

import java.time.LocalDateTime;

//Told of every report of the flow rate of the valve, as it arrives (HAS-320): the flow alarms judge a pause shorter than
//a minute, which the minute pass alone would not see. Called on the thread of the report, so it must not block
public interface FlowListener {

    //the flow in l/min, at the time of the report on the clock of the house
    void flow(double litresPerMinute, LocalDateTime at);

    //nothing more is known of the flow: the last will of the valve, or another valve in the registry
    void forget();

    FlowListener NONE = new FlowListener() {
        @Override
        public void flow(final double litresPerMinute, final LocalDateTime at) {
            //nobody listens
        }

        @Override
        public void forget() {
            //nobody listens
        }
    };
}
