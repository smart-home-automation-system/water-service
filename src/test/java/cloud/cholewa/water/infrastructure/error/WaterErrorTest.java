package cloud.cholewa.water.infrastructure.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//The codes are wire contract once a caller branches on them - the dashboard words them (HAS-316; TANK_COLD HAS-404; SOFTENER_UNAVAILABLE HAS-397; UNKNOWN_METRIC HAS-398; the commands of the softener HAS-400; LIVE_THROTTLED HAS-401)
class WaterErrorTest {

    @Test
    void should_keep_the_codes_the_callers_branch_on() {
        assertThat(Arrays.stream(WaterError.values()).map(ErrorId::codeOf))
            .containsExactly("NOT_FOUND_VALVE", "REGISTRY_UNAVAILABLE", "INVALID_RANGE", "INVALID_BILL", "NOT_FOUND_BILL",
                "INVALID_IMPORT", "INVALID_COMMAND", "DEVICE_FAILED", "TANK_COLD",
                "SOFTENER_UNAVAILABLE", "UNKNOWN_METRIC", "SOFTENER_COMMAND_FAILED", "REGENERATION_IN_PROGRESS",
                "COMMAND_TOO_SOON", "LIVE_THROTTLED");
    }
}
