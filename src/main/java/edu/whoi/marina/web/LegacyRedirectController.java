package edu.whoi.marina.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Old bookmarks/links from before the Schedule/Dashboard swap (calendar.html became the
 *  homepage, index.html) — redirect rather than let them break. */
@RestController
public class LegacyRedirectController {

    @GetMapping("/calendar.html")
    public ResponseEntity<Void> calendarRedirect() {
        return ResponseEntity.status(301).location(URI.create("/")).build();
    }
}
