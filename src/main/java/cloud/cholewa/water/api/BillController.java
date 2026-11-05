package cloud.cholewa.water.api;

import cloud.cholewa.water.model.BillReply;
import cloud.cholewa.water.model.BillRequest;
import cloud.cholewa.water.service.BillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//The bills of the waterworks (HAS-318), routed by the gateway under /home/water/** - no gateway release. The rules of a
//bill are checked by the service (INVALID_BILL with its reason), not by Bean Validation, so every refusal has its code
@Slf4j
@RestController
@RequiredArgsConstructor
public class BillController {

    private final BillService billService;

    @GetMapping("bills")
    Flux<BillReply> getBills() {
        return billService.all()
            .doOnSubscribe(subscription -> log.info("Querying the water bills"));
    }

    @GetMapping("bills/{id}")
    Mono<ResponseEntity<BillReply>> getBill(@PathVariable final long id) {
        return billService.one(id)
            .doOnSubscribe(subscription -> log.info("Querying the water bill {}", id))
            .map(ResponseEntity::ok);
    }

    @PostMapping("bills")
    Mono<ResponseEntity<BillReply>> createBill(@RequestBody final BillRequest bill) {
        return billService.create(bill)
            .doOnSubscribe(subscription -> log.info("Entering a water bill {} - {}", bill.periodFrom(), bill.periodTo()))
            .map(reply -> ResponseEntity.status(HttpStatus.CREATED).body(reply));
    }

    @PutMapping("bills/{id}")
    Mono<ResponseEntity<BillReply>> updateBill(@PathVariable final long id, @RequestBody final BillRequest bill) {
        return billService.update(id, bill)
            .doOnSubscribe(subscription -> log.info("Replacing the water bill {}", id))
            .map(ResponseEntity::ok);
    }

    @DeleteMapping("bills/{id}")
    Mono<ResponseEntity<Void>> deleteBill(@PathVariable final long id) {
        return billService.delete(id)
            .doOnSubscribe(subscription -> log.info("Deleting the water bill {}", id))
            .thenReturn(ResponseEntity.noContent().build());
    }
}
