package edu.whoi.marina.web;

import edu.whoi.marina.service.AppStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Polled by the frontend's loading screen so it can show real startup progress instead of a
 *  blank or misleadingly-empty page while the backend is still importing/validating. */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final AppStatusService statusService;

    public StatusController(AppStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping
    public AppStatusService.StatusSnapshot status() {
        return statusService.snapshot();
    }
}
