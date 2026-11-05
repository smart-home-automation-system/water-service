package cloud.cholewa.water.presence;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

//Whether anybody of the household is at home, as presence-service says it (HAS-320; judge is HouseState.judge of
//light-service, HAS-263 - a copy). Unknown is never empty
public enum HouseOccupancy {
    EMPTY("empty"), OCCUPIED("occupied"), UNKNOWN("unknown");

    //presence-service checks the household every minute: a reading older than this is not a reading
    static final Duration FRESH = Duration.ofMinutes(5);

    private final String value;

    HouseOccupancy(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    //empty: every active member is away, each by a reading newer than FRESH; a member presence-service has never
    //observed is not away; no members at all, or a call that failed (null), is not known
    public static HouseOccupancy judge(final List<ResidentPresence> residents, final LocalDateTime now) {
        if (residents == null || residents.isEmpty()) {
            return UNKNOWN;
        }
        if (residents.stream().anyMatch(resident -> Boolean.TRUE.equals(resident.present()))) {
            return OCCUPIED;
        }
        final boolean everyAwayAndFresh = residents.stream().allMatch(resident -> Boolean.FALSE.equals(resident.present())
            && resident.lastCheckedAt() != null && !resident.lastCheckedAt().isBefore(now.minus(FRESH)));
        return everyAwayAndFresh ? EMPTY : UNKNOWN;
    }
}
