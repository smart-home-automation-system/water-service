package cloud.cholewa.water.model;

import java.time.LocalDateTime;

/**
 * A command the water valve took (HAS-321, {@code POST /home/water/valve}, 202) - taken is not done: the report of the
 * valve answers it, {@code lastCommandConfirmed} of {@code GET /home/water/valve}.<br>
 * {@code state} - {@code open} or {@code close}, as asked.<br>
 * {@code source} - who asked.<br>
 * {@code commandedAt} - when it was sent, on the clock of the house, without an offset, to the second.
 */
public record ValveCommandReply(String state, String source, LocalDateTime commandedAt) {
}
