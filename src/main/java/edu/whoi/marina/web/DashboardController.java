package edu.whoi.marina.web;

import edu.whoi.marina.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardService.DashboardSummary summary(@RequestParam(defaultValue = "false") boolean hideHistorical) {
        return service.summary(hideHistorical);
    }
}
