package cloud.cholewa.water.command;

import java.time.LocalDateTime;
import java.util.Optional;

//What is known of the commands of the water valve, in memory (HAS-321, the rule of blinds-service HAS-250), without I/O -
//pinned by CommandWatchTest.
//pending - the command waiting for its report, null when none; a newer command takes its place, the older is neither
//confirmed nor counted. lastConfirmed - null until a command was sent since the start, false from its sending until a
//report answers it and when none did within its window, true once one did. inARow - the unconfirmed commands in a row,
//ended by a confirmed one. announced - the alert of this run of unconfirmed commands got through
record CommandWatch(ValveCommand pending, Boolean lastConfirmed, int inARow, boolean announced) {

    static final CommandWatch NONE = new CommandWatch(null, null, 0, false);

    CommandWatch issued(final ValveCommand command) {
        return new CommandWatch(command, false, inARow, announced);
    }

    //a report of the valve: the pending command is confirmed when it answers it
    Optional<CommandWatch> reported(final boolean open, final LocalDateTime at) {
        return Optional.ofNullable(pending)
            .filter(command -> command.answeredBy(open, at))
            .map(command -> new CommandWatch(null, true, 0, false));
    }

    //the window of a command is over: unconfirmed when it is still pending, nothing for one confirmed or replaced
    Optional<CommandWatch> expired(final ValveCommand command) {
        return Optional.ofNullable(pending)
            .filter(waiting -> waiting.at().equals(command.at()) && waiting.open() == command.open())
            .map(waiting -> new CommandWatch(null, false, inARow + 1, announced));
    }

    CommandWatch withAnnounced() {
        return new CommandWatch(pending, lastConfirmed, inARow, true);
    }
}
