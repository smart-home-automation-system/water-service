package cloud.cholewa.water.softener;

import java.util.List;

//One page of the regeneration events of the iQua cloud (HAS-398): the events in the order of the cloud and the total it
//counts (0 when it gave none)
public record IquaRegenerationPage(List<IquaRegeneration> events, long total) {
}
