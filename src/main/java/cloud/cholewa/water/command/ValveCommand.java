package cloud.cholewa.water.command;

import java.time.Duration;
import java.time.LocalDateTime;

//A command the water valve took (HAS-321): open (true) or close, by its source, sent at; rowId - its row of
//water_valve_command, null when the database did not take it. The window is the time a report has to answer it
public record ValveCommand(Long rowId, boolean open, String source, LocalDateTime at) {

    //a report of the state of boolean:200 arrives at once with MQTT, at the latest with the read of every minute
    public static final Duration WINDOW = Duration.ofSeconds(60);

    public String state() {
        return open ? "open" : "close";
    }

    //a report of the valve in the state this command asks, made after it was sent and within its window
    boolean answeredBy(final boolean reportedOpen, final LocalDateTime reportedAt) {
        return reportedOpen == open && !reportedAt.isBefore(at) && !reportedAt.isAfter(at.plus(WINDOW));
    }
}
