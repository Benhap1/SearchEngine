package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.config.SitesList;
import searchengine.dto.search.SearchResults;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.service.IndexPageCommand;
import searchengine.service.SearchCommand;
import searchengine.service.SiteIndexingService;
import searchengine.services.StatisticsService;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {


    private final StatisticsService statisticsService;
    private final SiteIndexingService siteIndexingService;
    private final SitesList sitesList;
    private final IndexPageCommand indexPageCommand;
    private final SearchCommand searchCommand;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }


    @GetMapping("/startIndexing")
    public ResponseEntity<String> startIndexing() {
        String response = siteIndexingService.startIndexing(sitesList.getSites());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<String> stopIndexing() {
        String response = siteIndexingService.stopIndex();
        return ResponseEntity.ok(response);
    }


    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url) {
        Map<String, Object> response = indexPageCommand.processIndexPage(url);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit) {

        SearchResults searchResults = searchCommand.search(query, site, offset, limit);
        return ResponseEntity.ok(searchResults);
    }
}