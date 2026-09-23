package edu.whoi.marina.importer;

import edu.whoi.marina.config.MarinaProperties;
import edu.whoi.marina.domain.Reservation;
import edu.whoi.marina.store.JsonCollectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seeds the JSON store from the source workbook on first startup (when reservations.json is
 * still empty) or whenever MARINA_FORCE_REIMPORT=true / --reimport is passed. Safe to leave in
 * place permanently: it's a no-op once real data exists, so it never clobbers live edits.
 */
@Component
public class ImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final SpreadsheetImportService importService;
    private final JsonCollectionStore<Reservation> reservationStore;
    private final MarinaProperties properties;

    public ImportRunner(SpreadsheetImportService importService,
                         JsonCollectionStore<Reservation> reservationStore,
                         MarinaProperties properties) {
        this.importService = importService;
        this.reservationStore = reservationStore;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean forceReimport = properties.getImport().isForceReimport() || args.containsOption("reimport");
        boolean alreadySeeded = !reservationStore.findAll().isEmpty();

        if (alreadySeeded && !forceReimport) {
            log.info("Reservation store already has data — skipping historical import.");
            return;
        }

        String sourcePath = properties.getImport().getSourceXlsx();
        if (!Files.exists(Path.of(sourcePath))) {
            log.warn("Source workbook not found at {} — starting with an empty database.", sourcePath);
            return;
        }

        log.info("Importing historical schedule from {} ...", sourcePath);
        ImportSummary summary = importService.importFrom(sourcePath);
        log.info("Import complete.\n{}", summary);
    }
}
