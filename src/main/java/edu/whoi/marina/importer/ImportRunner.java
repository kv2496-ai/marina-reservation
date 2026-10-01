package edu.whoi.marina.importer;

import edu.whoi.marina.config.MarinaProperties;
import edu.whoi.marina.domain.Reservation;
import edu.whoi.marina.service.AppStatusService;
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
 *
 * The embedded server starts accepting HTTP requests before this runner finishes (Spring Boot
 * runs ApplicationRunners after the web server is already up), so there's a real window where the
 * API is reachable but the data isn't there yet. AppStatusService exists so the frontend can show
 * that honestly instead of a misleadingly-empty page.
 */
@Component
public class ImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final SpreadsheetImportService importService;
    private final JsonCollectionStore<Reservation> reservationStore;
    private final MarinaProperties properties;
    private final AppStatusService statusService;

    public ImportRunner(SpreadsheetImportService importService,
                         JsonCollectionStore<Reservation> reservationStore,
                         MarinaProperties properties,
                         AppStatusService statusService) {
        this.importService = importService;
        this.reservationStore = reservationStore;
        this.properties = properties;
        this.statusService = statusService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean forceReimport = properties.getImport().isForceReimport() || args.containsOption("reimport");
        boolean alreadySeeded = !reservationStore.findAll().isEmpty();

        if (alreadySeeded && !forceReimport) {
            log.info("Reservation store already has data — skipping historical import.");
            statusService.markReady("Data already loaded.");
            return;
        }

        String sourcePath = properties.getImport().getSourceXlsx();
        if (!Files.exists(Path.of(sourcePath))) {
            log.warn("Source workbook not found at {} — starting with an empty database.", sourcePath);
            statusService.markReady("No source workbook found — starting empty.");
            return;
        }

        log.info("Importing historical schedule from {} ...", sourcePath);
        statusService.markImporting("Importing the historical schedule spreadsheet…");
        ImportSummary summary = importService.importFrom(sourcePath);
        log.info("Import complete.\n{}", summary);
        statusService.markReady("Ready.");
    }
}
