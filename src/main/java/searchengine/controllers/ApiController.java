package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.config.SitesList;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.service.SiteIndexingService;
import searchengine.services.StatisticsService;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

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

    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url) {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean result = siteIndexingService.indexPage(url);
            if (!result) {
                response.put("result", false);
                response.put("error", "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
                return ResponseEntity.ok(response);
            }

            response.put("result", true);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("result", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
