package cloud.cholewa.water.service;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

//The reads of the history of the softener one after the other (HAS-398, a copy of OneAtATime of weather-service and
//energy-service - the third, so it stays a copy): the pool has two connections, and two ranges at once
//would leave none for the reading of the softener, the snapshot of the valve or its alarms. Each query waits for the
//end of the one before it - completed, failed or cancelled - without blocking a thread: the turn is a
//Mono that completes when the work before it ends. A query cancelled while it waits hands its turn on
//only once the one before it has ended too - never two queries at once
final class OneAtATime {

    private final AtomicReference<Mono<Void>> last = new AtomicReference<>(Mono.empty());

    <T> Mono<T> run(final Mono<T> work) {
        return Mono.defer(() -> {
            final Sinks.Empty<Void> done = Sinks.empty();
            final Mono<Void> before = last.getAndSet(done.asMono());
            return before
                .then(work)
                .doFinally(signal -> before.subscribe(null, null, done::tryEmitEmpty));
        });
    }
}
