package cloud.cholewa.water.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One regeneration of {@code GET /home/water/softener/regenerations} (HAS-398), as the iQua cloud reported it.<br>
 * {@code startedAt}, {@code endedAt} - house time; {@code endedAt} left out while the regeneration goes on.<br>
 * {@code deviceStartedAt} - the start as the clock of the softener had it; left out when the cloud gave none.<br>
 * {@code running} - the regeneration goes on (no end yet).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftenerRegenerationReply(
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    LocalDateTime deviceStartedAt,
    boolean running
) {
}
