package cloud.cholewa.water.softener;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

//The frames of the live stream (HAS-401), decoded from the capture of the real socket of 2026-11-09 (iqua/live-frames.json):
//of the 16 frames only the flow is taken, by its converted value in litres a minute
class LiveFrameTest {

    private static List<String> captured() {
        final JsonNode frames = SoftenerSamples.JSON.readTree(SoftenerSamples.resource("/iqua/live-frames.json"));
        return StreamSupport.stream(frames.spliterator(), false).map(JsonNode::toString).toList();
    }

    @Test
    void should_take_only_the_flow_of_the_real_stream() {
        final List<LiveFrame> flows = captured().stream()
            .map(text -> LiveFrame.flow(text, SoftenerSamples.JSON))
            .flatMap(Optional::stream)
            .toList();

        assertThat(captured()).hasSize(16);
        assertThat(flows).containsExactly(new LiveFrame(0.0, Instant.parse("2026-11-09T18:15:59.351506744Z")));
    }

    //a frame of water running, in the shape of the real one: the converted value, not the tenths of a gpm
    @Test
    void should_take_the_converted_litres_of_a_flow() {
        final String running = """
            {"name":"current_water_flow_gpm","type":"property","value":32,
             "converted_property":{"value":12.113318,"units":"liters/min"},"timestamp":"2026-11-09T18:20:00Z"}""";

        assertThat(LiveFrame.flow(running, SoftenerSamples.JSON))
            .contains(new LiveFrame(12.1, Instant.parse("2026-11-09T18:20:00Z")));
    }

    @Test
    void should_take_nothing_of_a_frame_it_cannot_read() {
        assertThat(LiveFrame.flow("not json", SoftenerSamples.JSON)).isEmpty();
        assertThat(LiveFrame.flow("{\"name\":\"current_water_flow_gpm\",\"type\":\"property\",\"value\":3,"
            + "\"converted_property\":null}", SoftenerSamples.JSON)).isEmpty();
        assertThat(LiveFrame.flow("{\"name\":\"current_water_flow_gpm\",\"type\":\"property\",\"value\":3,"
            + "\"converted_property\":{\"value\":1.1,\"units\":\"gpm\"}}", SoftenerSamples.JSON)).isEmpty();
        assertThat(LiveFrame.flow("{\"name\":\"app_active\",\"type\":\"property\",\"value\":true}", SoftenerSamples.JSON))
            .isEmpty();
    }

    //a frame without its time is dated by its arrival
    @Test
    void should_leave_the_time_out_of_a_frame_without_one() {
        assertThat(LiveFrame.flow("{\"name\":\"current_water_flow_gpm\",\"type\":\"property\","
            + "\"converted_property\":{\"value\":3.0,\"units\":\"liters/min\"}}", SoftenerSamples.JSON))
            .contains(new LiveFrame(3.0, null));
    }
}
