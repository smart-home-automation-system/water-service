package cloud.cholewa.water.softener;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

//The WARNs of a read repeated every few minutes (HAS-397, a copy of FailureLog of energy-service - the second, so it stays
//a copy): the first failure of a kind is a WARN, the same kind again at most once an hour while it lasts, another kind at
//once; the first success after failures is an INFO. So a wrong password or a cloud that is down is one WARN an hour
public final class FailureLog {

    static final Duration REPEAT_AFTER = Duration.ofHours(1);

    private final Logger log;
    private final Clock clock;
    private final AtomicReference<Said> said = new AtomicReference<>();

    public FailureLog(final Logger log, final Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    private record Said(String kind, Instant at) {
    }

    public void failed(final String what, final Throwable failure) {
        final Instant now = clock.instant();
        final String kind = kindOf(failure);
        final Said before = said.get();
        if (before == null || !before.kind().equals(kind) || !now.isBefore(before.at().plus(REPEAT_AFTER))) {
            said.set(new Said(kind, now));
            log.warn("The {} could not be read: {}", what, failure.getMessage());
        }
    }

    //the class and the cause before its details: "The iQua cloud did not answer for the softener: <the exception>" is one
    //kind whatever the exception says, "The iQua cloud answered HTTP 500 for the softener" another
    static String kindOf(final Throwable failure) {
        final String message = String.valueOf(failure.getMessage());
        final int details = message.indexOf(": ");
        return failure.getClass().getName() + " " + (details < 0 ? message : message.substring(0, details));
    }

    public void succeeded(final String what) {
        if (said.getAndSet(null) != null) {
            log.info("The {} is read again", what);
        }
    }
}
