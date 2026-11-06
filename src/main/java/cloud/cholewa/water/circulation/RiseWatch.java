package cloud.cholewa.water.circulation;

import cloud.cholewa.commons.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

//Whether the circulation warms the return (HAS-405): noRiseRuns runs in a row stopped by the limit from a cold return
//(below startBelow, both temperatures known) whose return rose by less than minRise are an alert on the technical channel
//(a pump that does not turn, a sensor off the pipe); the next run that warms - by minRise, or up to the target (stopped
//by the temperature) - is an info. By the rise, not by the limit (owner 2026-11-06): with the tank at about 43 C the
//return rarely reaches its target within a run. A run started warm (a manual one) rises little by nature, and a run
//ended by the device or a failure says nothing of the loop: both leave the count as it is. In memory, like the plugs of appliance-service: a
//restart starts the count anew. The state moves only once the notice was published - one that failed is due with the
//next run; a notice neither fails nor stalls the end of the run (cut off after NOTICE_TIMEOUT)
@Slf4j
@Component
@RequiredArgsConstructor
class RiseWatch {

    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(30);

    //what a run says of the loop: the rise of its return, and whether it warmed
    private record Verdict(double rise, boolean warmed) {
    }

    private final NotificationPublisher notificationPublisher;
    private final AutomationProperties properties;

    private int flat;
    private boolean alerted;

    Mono<Void> ended(final String reason, final Double returnAtStart, final Double returnAtEnd) {
        return Mono.justOrEmpty(rise(reason, returnAtStart, returnAtEnd))
            .flatMap(this::judge)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The rise of the return of the circulation was not judged: {}", failure.toString())));
    }

    private Optional<Verdict> rise(final String reason, final Double start, final Double end) {
        if (start == null || end == null) {
            return Optional.empty();
        }
        if (CirculationRules.TEMPERATURE.equals(reason)) {
            return Optional.of(new Verdict(end - start, true));
        }
        if (CirculationRules.LIMIT.equals(reason) && start < properties.startBelow()) {
            return Optional.of(new Verdict(end - start, end - start >= properties.minRise()));
        }
        return Optional.empty();
    }

    //one of the two holds: a flat run counts, a warming one ends the count
    private Mono<Void> judge(final Verdict verdict) {
        return Mono.just(verdict)
            .filter(run -> !run.warmed())
            .flatMap(run -> flat(run.rise()))
            .then(Mono.just(verdict)
                .filter(Verdict::warmed)
                .flatMap(run -> rose(run.rise())));
    }

    private Mono<Void> flat(final double rise) {
        final int count = count();
        log.info("The return of the circulation rose by {} C in the run - {} run(s) in a row below {} C",
            celsius(rise), count, celsius(properties.minRise()));
        return Mono.just(count)
            .filter(runs -> runs >= properties.noRiseRuns() && !isAlerted())
            .flatMap(runs -> publish(notificationPublisher.publishAlert(alertText(runs, rise)), true));
    }

    private Mono<Void> rose(final double rise) {
        reset();
        return Mono.just(rise)
            .filter(warming -> isAlerted())
            .flatMap(warming -> publish(notificationPublisher.publishInfo(infoText(warming)), false));
    }

    private Mono<Void> publish(final Mono<Void> notice, final boolean alert) {
        return notice
            .timeout(NOTICE_TIMEOUT)
            .then(Mono.fromRunnable(() -> {
                setAlerted(alert);
                log.info("Circulation notification published: {}", alert ? "the return does not warm" : "the return warms again");
            }))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The circulation notification was not published, tried again with the next run: {}", failure.toString())));
    }

    private String alertText(final int runs, final double rise) {
        return "Circulation: the return did not warm in " + runs + " runs in a row (the last by " + celsius(rise)
            + " °C, below " + celsius(properties.minRise()) + " °C) - check the circulation pump and the sensor on the return.";
    }

    private static String infoText(final double rise) {
        return "Circulation: the return warms again - by " + celsius(rise) + " °C in the last run.";
    }

    private static String celsius(final double degrees) {
        return String.format(Locale.ROOT, "%.1f", degrees);
    }

    private synchronized int count() {
        return ++flat;
    }

    private synchronized void reset() {
        flat = 0;
    }

    private synchronized boolean isAlerted() {
        return alerted;
    }

    private synchronized void setAlerted(final boolean value) {
        alerted = value;
    }
}
