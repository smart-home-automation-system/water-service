package cloud.cholewa.water.softener;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//How often a login may be tried (HAS-397): three failures within 30 min hold the next for 30 min after the last of them;
//a success forgets them - the cloud blocks an account that calls too often
class LoginGuardTest {

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final LoginGuard guard = new LoginGuard(clock);

    @Test
    void should_let_a_login_through_after_two_failures() {
        guard.failed();
        clock.moveBy(Duration.ofMinutes(5));
        guard.failed();

        assertThat(guard.heldUntil()).isEmpty();
    }

    @Test
    void should_hold_the_logins_for_thirty_minutes_after_the_third_failure() {
        guard.failed();
        clock.moveBy(Duration.ofMinutes(5));
        guard.failed();
        clock.moveBy(Duration.ofMinutes(5));
        guard.failed();

        assertThat(guard.heldUntil()).contains(SoftenerSamples.START.plus(Duration.ofMinutes(40)));
        clock.moveBy(Duration.ofMinutes(29));
        assertThat(guard.heldUntil()).isPresent();
        clock.moveBy(Duration.ofMinutes(1));
        assertThat(guard.heldUntil()).isEmpty();
    }

    //after the hold a single failure does not hold again at once
    @Test
    void should_count_anew_after_the_hold() {
        guard.failed();
        guard.failed();
        guard.failed();
        clock.moveBy(Duration.ofMinutes(30));

        guard.failed();

        assertThat(guard.heldUntil()).isEmpty();
    }

    @Test
    void should_forget_a_failure_older_than_thirty_minutes() {
        guard.failed();
        clock.moveBy(Duration.ofMinutes(20));
        guard.failed();
        clock.moveBy(Duration.ofMinutes(11));
        guard.failed();

        assertThat(guard.heldUntil()).isEmpty();
    }

    @Test
    void should_forget_the_failures_after_a_success() {
        guard.failed();
        guard.failed();
        guard.succeeded();
        guard.failed();

        assertThat(guard.heldUntil()).isEmpty();
    }
}
