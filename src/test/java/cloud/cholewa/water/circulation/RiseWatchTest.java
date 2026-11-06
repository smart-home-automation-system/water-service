package cloud.cholewa.water.circulation;

import cloud.cholewa.commons.notification.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//Whether the circulation warms the return (HAS-405): three runs in a row stopped by the limit from a cold return below
//the rise of 1.5 C are one alert, a run that warms an info; a run started warm, ended by the device or a failure, or
//without its temperatures, says nothing
class RiseWatchTest {

    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private RiseWatch sut;

    @BeforeEach
    void setUp() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        sut = new RiseWatch(publisher, AutomationRulesTest.AUTOMATION);
    }

    private void ended(final String reason, final Double start, final Double end) {
        sut.ended(reason, start, end).as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_alert_once_after_three_runs_in_a_row_that_did_not_warm_the_return() {
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 27.0);
        verify(publisher, never()).publishAlert(anyString());

        ended("limit", 26.0, 27.4);
        ended("limit", 26.0, 26.2);

        verify(publisher, times(1)).publishAlert("Circulation: the return did not warm in 3 runs in a row (the last by "
            + "1.4 °C, below 1.5 °C) - check the circulation pump and the sensor on the return.");
    }

    @Test
    void should_tell_the_return_warms_again_after_an_alert() {
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);

        ended("limit", 26.0, 33.7);

        verify(publisher).publishInfo("Circulation: the return warms again - by 7.7 °C in the last run.");
    }

    @Test
    void should_start_the_count_anew_after_a_run_that_warmed() {
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.4, 33.69);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);

        verify(publisher, never()).publishAlert(anyString());
        verify(publisher, never()).publishInfo(anyString());
    }

    //the device, a failure, a temperature not read: nothing about the loop
    @Test
    void should_leave_the_count_alone_for_a_run_that_says_nothing_of_the_loop() {
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("device", 26.0, 26.1);
        ended("failure", 26.0, null);
        ended("limit", null, 26.5);
        verify(publisher, never()).publishAlert(anyString());

        ended("limit", 26.0, 26.5);
        verify(publisher).publishAlert(anyString());
    }

    //the target reached is a loop that warms, whatever the rise; a manual run from a warm return rises little by nature
    @Test
    void should_judge_a_run_that_reached_the_target_as_warming_and_leave_a_warm_start_alone() {
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);

        ended("temperature", 34.0, 35.1);
        verify(publisher).publishInfo("Circulation: the return warms again - by 1.1 °C in the last run.");

        ended("limit", 33.8, 34.9);
        ended("limit", 33.8, 34.9);
        ended("limit", 33.8, 34.9);
        verify(publisher, times(1)).publishAlert(anyString());
    }

    //a notice that failed changes nothing: the next flat run tries again, and the end of the run never fails
    @Test
    void should_try_the_alert_again_with_the_next_run_when_it_was_not_published() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")))
            .thenReturn(Mono.empty());
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);
        ended("limit", 26.0, 26.5);

        verify(publisher, times(2)).publishAlert(anyString());
    }
}
