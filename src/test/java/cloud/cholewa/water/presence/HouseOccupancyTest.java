package cloud.cholewa.water.presence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//Whether the house is empty, as presence-service says it (HAS-320, the rule of light-service): unknown is never empty
class HouseOccupancyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 10, 0);

    @Test
    void should_be_empty_when_every_member_is_away_by_a_fresh_reading() {
        assertThat(HouseOccupancy.judge(List.of(away("Alex", NOW.minusMinutes(1)), away("Sam", NOW.minusMinutes(5))),
            NOW)).isEqualTo(HouseOccupancy.EMPTY);
    }

    @Test
    void should_be_occupied_when_one_member_is_at_home() {
        assertThat(HouseOccupancy.judge(List.of(away("Alex", NOW), new ResidentPresence("Sam", true, NOW, null)), NOW))
            .isEqualTo(HouseOccupancy.OCCUPIED);
    }

    //a reading older than five minutes is not a reading, a member never observed is not away
    @Test
    void should_not_know_by_a_reading_that_is_not_fresh_or_a_member_never_observed() {
        assertThat(HouseOccupancy.judge(List.of(away("Alex", NOW.minusMinutes(5).minusSeconds(1))), NOW))
            .isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(HouseOccupancy.judge(List.of(away("Alex", NOW), new ResidentPresence("Sam", null, null, null)), NOW))
            .isEqualTo(HouseOccupancy.UNKNOWN);
    }

    //a household without members, or a call that failed: not known, never empty
    @Test
    void should_not_know_without_residents() {
        assertThat(HouseOccupancy.judge(List.of(), NOW)).isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(HouseOccupancy.judge(null, NOW)).isEqualTo(HouseOccupancy.UNKNOWN);
    }

    private static ResidentPresence away(final String name, final LocalDateTime checkedAt) {
        return new ResidentPresence(name, false, NOW.minusHours(2), checkedAt);
    }
}
