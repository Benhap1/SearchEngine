package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.config.SitesList;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.service.SiteIndexingService;
import searchengine.services.StatisticsService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final SiteIndexingService siteIndexingService;
    private final SitesList sitesList;

    private boolean indexingInProgress = false; // Флаг, указывающий, идет ли индексация


    public ApiController(StatisticsService statisticsService, SiteIndexingService siteIndexingService, SitesList sitesList) {
        this.statisticsService = statisticsService;
        this.siteIndexingService = siteIndexingService;
        this.sitesList = sitesList;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<String> startIndexing() {

        if (indexingInProgress) {
            return ResponseEntity.badRequest().body("{\"result\": false, \"error\": \"Индексация уже запущена\"}");
        } else {
            indexingInProgress = true;
            siteIndexingService.indexSites(sitesList.getSites());
            indexingInProgress = false;
            return ResponseEntity.ok("{\"result\": true}");
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<?> stopIndexing() {
        if (!indexingInProgress) {
            return ResponseEntity.badRequest().body("{\"result\": false, \"error\": \"Индексация не запущена\"}");
        } else {
            indexingInProgress = false;
            siteIndexingService.stopIndexing();
            return ResponseEntity.ok("{\"result\": true}");
        }
    }
}
