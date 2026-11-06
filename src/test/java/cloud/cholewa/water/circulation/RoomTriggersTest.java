package cloud.cholewa.water.circulation;

import cloud.cholewa.water.circulation.RoomTriggers.Trigger;
import cloud.cholewa.water.light.LightRoom;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//What a read of light-service triggers (HAS-405): a motion newer than the last seen, a light switched on; the first read
//of a room is what was there already
class RoomTriggersTest {

    private static final String ROOM = "bathroom up";
    private static final LocalDateTime MOTION = LocalDateTime.of(2026, 11, 6, 7, 10);

    private final RoomTriggers sut = new RoomTriggers();

    private static LightRoom room(final Boolean anyOn, final LocalDateTime lastMotionAt) {
        return new LightRoom(ROOM, anyOn, new LightRoom.Occupancy(lastMotionAt));
    }

    @Test
    void should_trigger_nothing_on_the_first_read_of_a_room() {
        assertThat(sut.observe(ROOM, room(true, MOTION))).isEmpty();
    }

    @Test
    void should_trigger_a_run_on_a_new_motion_only() {
        sut.observe(ROOM, room(false, MOTION));

        assertThat(sut.observe(ROOM, room(false, MOTION))).isEmpty();
        assertThat(sut.observe(ROOM, room(false, MOTION.plusMinutes(5)))).contains(new Trigger("motion", ROOM));
        assertThat(sut.observe(ROOM, room(false, MOTION.plusMinutes(5)))).isEmpty();
    }

    //light-service after a restart knows no motion: the last one seen stays, and comes back as nothing new
    @Test
    void should_keep_the_last_motion_through_a_read_that_does_not_know_it() {
        sut.observe(ROOM, room(false, MOTION));

        assertThat(sut.observe(ROOM, room(false, null))).isEmpty();
        assertThat(sut.observe(ROOM, room(false, MOTION))).isEmpty();
        assertThat(sut.observe(ROOM, room(false, MOTION.minusMinutes(1)))).isEmpty();
    }

    @Test
    void should_trigger_a_run_when_the_motion_comes_after_none_was_known() {
        sut.observe(ROOM, new LightRoom(ROOM, false, null));

        assertThat(sut.observe(ROOM, room(false, MOTION))).contains(new Trigger("motion", ROOM));
    }

    @Test
    void should_trigger_a_run_when_a_light_is_switched_on() {
        sut.observe(ROOM, room(false, MOTION));

        assertThat(sut.observe(ROOM, room(true, MOTION))).contains(new Trigger("light", ROOM));
        assertThat(sut.observe(ROOM, room(true, MOTION))).isEmpty();
        assertThat(sut.observe(ROOM, room(false, MOTION))).isEmpty();
    }

    //a light not known is neither off nor on: on after it is no switch seen
    @Test
    void should_not_trigger_a_run_for_a_light_on_after_one_not_known() {
        sut.observe(ROOM, room(null, MOTION));

        assertThat(sut.observe(ROOM, room(true, MOTION))).isEmpty();
    }

    @Test
    void should_keep_the_rooms_apart() {
        sut.observe(ROOM, room(false, MOTION));
        sut.observe("bathroom down", new LightRoom("bathroom down", false, null));

        assertThat(sut.observe("bathroom down", new LightRoom("bathroom down", true, null)))
            .contains(new Trigger("light", "bathroom down"));
        assertThat(sut.observe(ROOM, room(false, MOTION))).isEmpty();
    }
}
