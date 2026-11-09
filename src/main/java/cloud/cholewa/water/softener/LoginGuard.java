package cloud.cholewa.water.softener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

//How often the client may log in to the iQua cloud (HAS-397): after FAILURES failed logins within WINDOW it waits HOLD after
//the last of them before the next - the cloud blocks an account above about 1000 calls a day, and a wrong password must
//never become a login every five minutes. A successful login forgets the failures. No I/O, pinned by LoginGuardTest
final class LoginGuard {

    static final int FAILURES = 3;
    static final Duration WINDOW = Duration.ofMinutes(30);
    static final Duration HOLD = Duration.ofMinutes(30);

    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();

    LoginGuard(final Clock clock) {
        this.clock = clock;
    }

    //the time until which no login may be tried; empty when one may be tried now
    synchronized Optional<Instant> heldUntil() {
        final Instant now = clock.instant();
        forgetOlderThan(now);
        return Optional.of(failures)
            .filter(recent -> recent.size() >= FAILURES)
            .map(recent -> recent.peekLast().plus(HOLD))
            .filter(until -> until.isAfter(now));
    }

    synchronized void failed() {
        final Instant now = clock.instant();
        forgetOlderThan(now);
        failures.addLast(now);
    }

    synchronized void succeeded() {
        failures.clear();
    }

    //a failure older than the window counts no more - unless it is one of a held run, whose hold is counted from the
    //last failure: kept until the hold after the last one is over
    private void forgetOlderThan(final Instant now) {
        final boolean holding = failures.size() >= FAILURES && failures.peekLast().plus(HOLD).isAfter(now);
        if (!holding) {
            while (!failures.isEmpty() && !failures.peekFirst().isAfter(now.minus(WINDOW))) {
                failures.removeFirst();
            }
        }
        if (failures.size() >= FAILURES && !failures.peekLast().plus(HOLD).isAfter(now)) {
            failures.clear();
        }
    }
}
