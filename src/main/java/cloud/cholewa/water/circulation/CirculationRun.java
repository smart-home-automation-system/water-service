package cloud.cholewa.water.circulation;

import java.time.LocalDateTime;

//A run of the pump the service started (HAS-404), held in memory while it goes on; id null when its row could not be
//stored - the run goes on all the same, its end is then not stored either. answered - the Uni answered the start; a start
//it did not answer may never have reached it, so a relay found off ends it as a failure, not as the device
record CirculationRun(Long id, LocalDateTime startedAt, String trigger, String source, Double returnAtStart,
                      boolean answered) {

    CirculationRun withId(final Long stored) {
        return new CirculationRun(stored, startedAt, trigger, source, returnAtStart, answered);
    }

    CirculationRun unanswered() {
        return new CirculationRun(id, startedAt, trigger, source, returnAtStart, false);
    }
}
