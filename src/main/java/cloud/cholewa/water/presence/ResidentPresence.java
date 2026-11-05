package cloud.cholewa.water.presence;

import java.time.LocalDateTime;

//Where an active member of the household is now, as presence-service answers it
//(GET /home/presence/residents/presence): lastCheckedAt is the last pass that confirmed it, null for a member it has
//never observed. A copy of light-service (HAS-263) - with appliance-service the third, the most the rule allows (HAS-320)
public record ResidentPresence(String name, Boolean present, LocalDateTime since, LocalDateTime lastCheckedAt) {
}
