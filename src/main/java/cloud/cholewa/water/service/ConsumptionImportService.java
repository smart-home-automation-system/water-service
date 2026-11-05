package cloud.cholewa.water.service;

import cloud.cholewa.water.database.repository.ConsumptionImportRepository;
import cloud.cholewa.water.model.ImportedDay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

//The daily consumption from before the readings of the house (HAS-318): the history of the Shelly cloud, imported once by
//hand - no integration with the cloud. A day must not be after the first day of the readings (today while there are
//none); on that first day the imported value wins over the measured part
@Service
@RequiredArgsConstructor
public class ConsumptionImportService {

    private final ConsumptionImportRepository consumptionImportRepository;
    private final Clock clock;

    public Flux<ImportedDay> all() {
        return consumptionImportRepository.findAll();
    }

    public Mono<Void> replace(final List<ImportedDay> days) {
        return consumptionImportRepository.firstReadingDay()
            .switchIfEmpty(Mono.fromSupplier(() -> LocalDate.now(clock)))
            .doOnNext(last -> BillRules.checkImport(days, last))
            .then(Mono.defer(() -> consumptionImportRepository.replaceAll(days)));
    }
}
