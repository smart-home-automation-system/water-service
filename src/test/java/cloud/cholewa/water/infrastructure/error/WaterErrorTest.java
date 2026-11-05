package cloud.cholewa.water.infrastructure.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//The codes are wire contract once a caller branches on them - the dashboard words them (HAS-316)
class WaterErrorTest {

    @Test
    void should_keep_the_codes_the_callers_branch_on() {
        assertThat(Arrays.stream(WaterError.values()).map(ErrorId::codeOf))
            .containsExactly("NOT_FOUND_VALVE", "REGISTRY_UNAVAILABLE", "INVALID_RANGE", "INVALID_BILL", "NOT_FOUND_BILL",
                "INVALID_IMPORT");
    }
}
