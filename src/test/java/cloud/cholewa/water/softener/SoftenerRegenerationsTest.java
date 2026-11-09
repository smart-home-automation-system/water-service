package cloud.cholewa.water.softener;

import cloud.cholewa.water.database.repository.SoftenerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The regenerations read once an hour (HAS-398): the pages read until one holds a regeneration already stored, a new one
//inserted, a running one completed by a later read, an ended one never written again; nothing before the softener is known
class SoftenerRegenerationsTest {

    private final SoftenerSamples.MovableClock clock = new SoftenerSamples.MovableClock();
    private final SoftenerReader reader = mock(SoftenerReader.class);
    private final IquaClient client = mock(IquaClient.class);
    private final SoftenerRepository repository = mock(SoftenerRepository.class);
    private final SoftenerRegenerations sut = new SoftenerRegenerations(reader, client, repository, clock);

    @BeforeEach
    void setUp() {
        when(reader.deviceId()).thenReturn(Optional.of("dev-1"));
        when(repository.recordRegeneration(anyString(), any(), any(), any())).thenReturn(Mono.just(true));
    }

    private static IquaRegeneration event(final int number, final boolean ended) {
        final String start = "2026-11-%02dT01:00:00Z".formatted(Math.max(1, 30 - number % 28));
        return new IquaRegeneration("r" + number, start, ended ? start.replace("T01", "T03") : null,
            start.replace("Z", ""));
    }

    private static List<IquaRegeneration> events(final int from, final int count) {
        return IntStream.range(from, from + count).mapToObj(number -> event(number, true)).toList();
    }

    //the first pass: nothing stored, every page read - the whole history once
    @Test
    void should_read_every_page_while_nothing_is_stored() {
        when(client.regenerations("dev-1", 1, 20)).thenReturn(Mono.just(new IquaRegenerationPage(events(0, 20), 45)));
        when(client.regenerations("dev-1", 2, 20)).thenReturn(Mono.just(new IquaRegenerationPage(events(20, 20), 45)));
        when(client.regenerations("dev-1", 3, 20)).thenReturn(Mono.just(new IquaRegenerationPage(events(40, 5), 45)));
        when(repository.knownRegenerations(anyCollection())).thenReturn(Mono.just(Map.of()));

        sut.read().as(StepVerifier::create).verifyComplete();

        verify(client).regenerations("dev-1", 3, 20);
        verify(repository).recordRegeneration(eq("r44"), any(), any(), any());
    }

    //a later pass: the first page holds a stored regeneration - no next page
    @Test
    void should_stop_at_the_page_that_holds_a_stored_regeneration() {
        when(client.regenerations("dev-1", 1, 20)).thenReturn(Mono.just(new IquaRegenerationPage(events(0, 20), 157)));
        when(repository.knownRegenerations(anyCollection())).thenReturn(Mono.just(Map.of("r1", true, "r2", true)));

        sut.read().as(StepVerifier::create).verifyComplete();

        verify(client, never()).regenerations("dev-1", 2, 20);
        verify(repository).recordRegeneration(eq("r0"), any(), any(), any());
        verify(repository, never()).recordRegeneration(eq("r1"), any(), any(), any());
    }

    //a regeneration that goes on is stored without an end and completed by the next read that has it
    @Test
    void should_complete_a_running_regeneration_later() {
        final IquaRegeneration running = new IquaRegeneration("r0", "2026-11-09T01:00:00Z", null, "2026-11-09T02:00:00");
        final IquaRegeneration ended = new IquaRegeneration("r0", "2026-11-09T01:00:00Z", "2026-11-09T03:00:00Z",
            "2026-11-09T02:00:00");
        when(client.regenerations("dev-1", 1, 20))
            .thenReturn(Mono.just(new IquaRegenerationPage(List.of(running), 1)),
                Mono.just(new IquaRegenerationPage(List.of(ended), 1)));
        when(repository.knownRegenerations(anyCollection()))
            .thenReturn(Mono.just(Map.of()), Mono.just(Map.of("r0", false)));
        final Mono<Void> pass = sut.read();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        //the start of the cloud (UTC) as the time of the house; the clock of the softener as it is written
        verify(repository).recordRegeneration(eq("r0"), eq(LocalDateTime.of(2026, 11, 9, 2, 0)), isNull(),
            eq(LocalDateTime.of(2026, 11, 9, 2, 0)));
        verify(repository).recordRegeneration("r0", LocalDateTime.of(2026, 11, 9, 2, 0),
            LocalDateTime.of(2026, 11, 9, 4, 0), LocalDateTime.of(2026, 11, 9, 2, 0));
    }

    @Test
    void should_skip_an_event_without_an_id_or_a_start() {
        when(client.regenerations("dev-1", 1, 20)).thenReturn(Mono.just(new IquaRegenerationPage(List.of(
            new IquaRegeneration(null, "2026-11-09T01:00:00Z", null, null),
            new IquaRegeneration("r1", null, null, null),
            new IquaRegeneration("r2", "not a time", null, null)), 3)));

        sut.read().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).recordRegeneration(anyString(), any(), any(), any());
    }

    @Test
    void should_read_nothing_before_the_softener_is_known() {
        when(reader.deviceId()).thenReturn(Optional.empty());

        sut.read().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client);
    }

    @Test
    void should_complete_when_the_cloud_fails() {
        when(client.regenerations(anyString(), anyInt(), anyInt()))
            .thenReturn(Mono.error(new IquaException("The iQua cloud answered HTTP 503 for the regenerations", 503)));

        sut.read().as(StepVerifier::create).verifyComplete();
    }

    //the first page of the real account read on 2026-11-09 (src/test/resources/iqua/regeneration-events.json): the newest
    //first, start_time / end_time in UTC, device_start_time with the offset of the softener - 02:01 of the house either way
    @Test
    void should_store_the_real_page_in_the_time_of_the_house() {
        final IquaRegenerationPage real = new IquaRegenerationPage(
            SoftenerSamples.JSON.readTree(SoftenerSamples.resource("/iqua/regeneration-events.json")).path("data")
                .valueStream().map(event -> SoftenerSamples.JSON.convertValue(event, IquaRegeneration.class)).toList(), 51);
        when(client.regenerations("dev-1", 1, 20)).thenReturn(Mono.just(real));
        when(client.regenerations("dev-1", 2, 20)).thenReturn(Mono.just(new IquaRegenerationPage(List.of(), 51)));
        when(repository.knownRegenerations(anyCollection())).thenReturn(Mono.just(Map.of()));

        sut.read().as(StepVerifier::create).verifyComplete();

        verify(repository).recordRegeneration("cb6b138b-d90d-45d6-8548-197c8b5dc051", LocalDateTime.of(2026, 11, 9, 2, 1, 51),
            LocalDateTime.of(2026, 11, 9, 4, 3, 46), LocalDateTime.of(2026, 11, 9, 2, 1, 51));
        //summer time: 00:01 UTC is 02:01 of the house, the softener says +02:00
        verify(repository).recordRegeneration("15f4ae48-4f42-4421-9cb0-3892980c08ea",
            LocalDateTime.of(2026, 10, 23, 2, 1, 52), LocalDateTime.of(2026, 10, 23, 4, 3, 47),
            LocalDateTime.of(2026, 10, 23, 2, 1, 52));
        verify(client).regenerations("dev-1", 2, 20);
    }

    @Test
    void should_read_the_times_of_the_cloud() {
        assertThat(sut.houseTime("2026-07-01T01:00:00Z")).contains(LocalDateTime.of(2026, 7, 1, 3, 0));
        assertThat(sut.houseTime("2026-07-01T03:00:00")).contains(LocalDateTime.of(2026, 7, 1, 3, 0));
        assertThat(sut.houseTime("")).isEmpty();
        assertThat(sut.houseTime(null)).isEmpty();
        assertThat(SoftenerRegenerations.deviceTime("2026-07-01T03:00:00Z")).contains(LocalDateTime.of(2026, 7, 1, 3, 0));
        assertThat(SoftenerRegenerations.deviceTime("2026-11-09T02:01:51+01:00"))
            .contains(LocalDateTime.of(2026, 11, 9, 2, 1, 51));
    }
}
