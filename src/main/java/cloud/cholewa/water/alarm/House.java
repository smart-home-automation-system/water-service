package cloud.cholewa.water.alarm;

import cloud.cholewa.water.presence.HouseOccupancy;

import java.time.Duration;
import java.time.LocalDateTime;

//Whether the house is empty, as the monitor of the alarms holds it (HAS-320), after HouseState of light-service: unknown
//after a start - never empty. emptySince - the first pass that read the house empty; an unknown spell (presence-service
//not read, a member not seen for a while) keeps it, so a short outage neither restarts the delay nor ends an empty
//house - only a member at home does. Without I/O
record House(HouseOccupancy occupancy, LocalDateTime emptySince) {

    static final House UNKNOWN = new House(HouseOccupancy.UNKNOWN, null);

    House read(final HouseOccupancy read, final LocalDateTime now) {
        return switch (read) {
            case EMPTY -> new House(read, emptySince == null ? now : emptySince);
            case OCCUPIED -> new House(read, null);
            case UNKNOWN -> new House(read, emptySince);
        };
    }

    //known to be empty now, and for at least after
    boolean emptyFor(final Duration after, final LocalDateTime now) {
        return occupancy == HouseOccupancy.EMPTY && emptySince != null && !emptySince.plus(after).isAfter(now);
    }
}
