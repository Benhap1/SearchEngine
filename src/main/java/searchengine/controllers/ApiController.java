package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import searchengine.config.SitesList;
import searchengine.dto.search.ErrorResponse;
import searchengine.dto.search.SearchResults;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.service.SiteIndexingService;
import searchengine.services.StatisticsService;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {


    private final StatisticsService statisticsService;
    private final SiteIndexingService siteIndexingService;
    private final SitesList sitesList;
    private boolean indexingInProgress = false; // Флаг, указывающий, идет ли индексация
    private final Object lock = new Object(); // Добавляем объект для синхронизации доступа к флагу


    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }


    @GetMapping("/startIndexing")
    public ResponseEntity<String> startIndexing() {
        synchronized (lock) {
            if (indexingInProgress) {
                return ResponseEntity.badRequest().body("{\"result\": false, \"error\": \"Индексация уже запущена\"}");
            } else {
                indexingInProgress = true;
            }
        }
        // Отправляем сообщение о начале индексации клиенту
        ResponseEntity<String> response = ResponseEntity.ok("{\"result\": true}");
        // Начинаем индексацию (асинхронно)
        new Thread(() -> {
            try {
                siteIndexingService.indexSites(sitesList.getSites());
            } finally {
                synchronized (lock) {
                    indexingInProgress = false; // Сбрасываем флаг после завершения индексации
                }
            }
        }).start();
        return response;
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<?> stopIndexing() {
        synchronized (lock) {
            if (!indexingInProgress) {
                return ResponseEntity.badRequest().body("{\"result\": false, \"error\": \"Индексация не запущена\"}");
            } else {
                indexingInProgress = false;
            }
        }
        siteIndexingService.stopIndexing();
        return ResponseEntity.ok("{\"result\": true}");
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


    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit) {

        if (query == null || query.isEmpty()) {
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setResult(false);
            errorResponse.setError("Задан пустой поисковый запрос");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            // Выполнение поиска с пагинацией
            SearchResults searchResults = siteIndexingService.search(query, site, offset, limit);
            return ResponseEntity.ok(searchResults);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", e);
        }
    }
}
