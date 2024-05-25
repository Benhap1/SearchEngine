package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@Service
public class SiteIndexingService {

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private PageRepository pageRepository;

    // Кэш для хранения результатов запросов по URL сайтов
    private final ConcurrentHashMap<String, SiteEntity> siteCache = new ConcurrentHashMap<>();

    // Кэш проверки существования URL страниц
    private final ConcurrentHashMap<String, Boolean> pageUrlCache = new ConcurrentHashMap<>();


    private AtomicInteger remainingPages; // Атомарная переменная для отслеживания оставшихся страниц

    @Transactional
    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайтов: {}", sites);
        remainingPages = new AtomicInteger(sites.size()); // Инициализируем счетчик

        List<CompletableFuture<Void>> indexingTasks = sites.stream()
                .map(this::indexSiteAsync)
                .toList();

        CompletableFuture<Void> allTasks = CompletableFuture.allOf(indexingTasks.toArray(new CompletableFuture[0]));

        try {
            allTasks.get(); // Ждем завершения всех задач
        } catch (InterruptedException | ExecutionException e) {
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        log.info("Конец индексации сайтов: {}", sites);
    }

    public CompletableFuture<Void> indexSiteAsync(Site site) {
        return CompletableFuture.runAsync(() -> indexSite(site));
    }

    @Transactional
    public void indexSite(Site site) {
        log.info("Начало индексации сайта: {}", site.getUrl());
        SiteEntity indexedSite = prepareSiteEntity(site); // Получаем или создаем запись о сайте
        if (indexedSite == null) {
            log.error("Не удалось создать запись для сайта: {}", site.getUrl());
            return;
        }
        String currentStatus = indexedSite.getStatus();
        log.info("Статус сайта до обновления: {}", indexedSite.getStatus());
        if (currentStatus.equals(SiteStatus.INDEXED.name()) || currentStatus.equals(SiteStatus.FAILED.name()) || currentStatus.equals(SiteStatus.INDEXING.name())) {
            indexedSite.setStatus(SiteStatus.INDEXING.name());
            indexedSite.setStatusTime(LocalDateTime.now());
            siteRepository.save(indexedSite);
            log.info("Статус сайта после обновления: {}", indexedSite.getStatus());
        }
        List<PageEntity> existingPages = pageRepository.findBySite(indexedSite);
        if (!existingPages.isEmpty()) {
            log.info("Удаление существующих записей страниц для сайта: {}", indexedSite.getUrl());
            pageRepository.deleteAll(existingPages);
        }
        indexPages(site.getUrl(), indexedSite); // Производим индексацию страниц
        updateSiteStatus(indexedSite); // Обновляем статус на INDEXED после завершения индексации
        log.info("Завершение индексации сайта: {}", site.getUrl());
    }

    @Transactional
    public void stopIndexing() {
        List<SiteEntity> sitesInProgress = siteRepository.findByStatus(SiteStatus.INDEXING.name());
        for (SiteEntity site : sitesInProgress) {
            site.setStatus(SiteStatus.FAILED.name());
            site.setLastError("Индексация остановлена пользователем");
            siteRepository.save(site);
        }
    }

    private void indexPages(String baseUrl, SiteEntity indexedSite) {
        log.info("Начало индексации страниц сайта: {}", baseUrl);
        try {
            Document document = Jsoup.connect(baseUrl).get();
            String baseUri = document.baseUri();

            ConcurrentHashMap<String, Boolean> visitedUrls = new ConcurrentHashMap<>();
            visitPage(document, baseUri, indexedSite, visitedUrls);
        } catch (IOException e) {
            handleIndexingError(indexedSite, e);
        } finally {
            log.info("Конец индексации страниц сайта: {}", baseUrl);
        }
    }

    private SiteEntity prepareSiteEntity(Site site) {
        log.info("Начало подготовки данных сайта: {}", site.getUrl());
        SiteEntity indexedSite = siteCache.computeIfAbsent(site.getUrl(), url -> {
            Optional<SiteEntity> existingSite = siteRepository.findByUrl(url);
            if (existingSite.isPresent()) {
                return existingSite.get();
            } else {
                SiteEntity newSite = new SiteEntity();
                newSite.setUrl(site.getUrl());
                newSite.setName(site.getName());
                newSite.setStatus(SiteStatus.INDEXING.name());
                newSite.setStatusTime(LocalDateTime.now());
                try {
                    siteRepository.save(newSite);
                    log.info("Создана запись для сайта: {}", site.getUrl());
                    return newSite;
                } catch (Exception e) {
                    log.error("Ошибка при создании записи для сайта {}: {}", site.getUrl(), e.getMessage());
                    return null;
                }
            }
        });
        log.info("Завершение подготовки данных сайта: {}", site.getUrl());
        return indexedSite;
    }

    private void visitPage(Document document, String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        log.info("Начало обработки страницы: {}", url);
        visitedUrls.putIfAbsent(url, true);

        if (pageUrlCache.putIfAbsent(url, true) != null) {
            log.info("Страница уже была обработана: {}", url);
            return;
        }

        PageEntity pageEntity = createPageEntity(document, url, siteEntity);
        if (pageEntity == null) {
            log.error("Не удалось создать запись для страницы: {}", url);
            return;
        }

        extractLinksAndIndexPages(document, siteEntity, visitedUrls);

        int remaining = remainingPages.decrementAndGet();
        if (remaining == 0) {
            log.info("Все страницы сайта обработаны. Завершение индексации.");
        }

        log.info("Завершение обработки страницы: {}", url);
    }

    private PageEntity createPageEntity(Document document, String url, SiteEntity siteEntity) {
        log.info("Начало создания записи страницы: {}", url);
        String path;
        try {
            URL parsedUrl = new URL(url);
            path = parsedUrl.getPath();
        } catch (MalformedURLException e) {
            log.error("Ошибка при разборе URL: {}", e.getMessage());
            return null;
        }

        if (path == null || path.isEmpty()) {
            log.error("Не удалось извлечь путь страницы из URL: {}", url);
            return null;
        }

        Optional<PageEntity> existingPage = pageRepository.findBySiteAndPath(siteEntity, path);
        if (existingPage.isPresent()) {
            log.info("Запись для страницы уже существует: {}", url);
            return existingPage.get();
        }

        PageEntity pageEntity = new PageEntity();
        pageEntity.setSite(siteEntity);
        pageEntity.setPath(path);

        try {
            int statusCode = Jsoup.connect(url).execute().statusCode();
            pageEntity.setCode(statusCode);
            pageEntity.setContent(document.outerHtml());
            pageRepository.save(pageEntity);
            log.info("Запись страницы успешно сохранена: {}", url);
            return pageEntity;
        } catch (IOException e) {
            log.error("Ошибка при получении статуса страницы {}: {}", url, e.getMessage());
        } catch (Exception e) {
            log.error("Ошибка при сохранении содержимого страницы {}: {}", url, e.getMessage());
        } finally {
            log.info("Завершение создания записи страницы: {}", url);
        }

        return null;
    }

    private void extractLinksAndIndexPages(Document document, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        log.info("Начало извлечения ссылок и индексации страниц: {}", document.baseUri());
        Elements links = document.select("a[href]");
        for (Element link : links) {
            String nextUrl = link.absUrl("href");
            if (visitedUrls.putIfAbsent(nextUrl, true) == null && isInternalLink(nextUrl, siteEntity.getUrl())) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Document nextDocument = Jsoup.connect(nextUrl).get();
                        visitPage(nextDocument, nextUrl, siteEntity, visitedUrls);
                    } catch (IOException e) {
                        log.error("Ошибка при получении страницы {}: {}", nextUrl, e.getMessage());
                    }
                });
            }
        }
        log.info("Завершение извлечения ссылок и индексации страниц: {}", document.baseUri());
    }

    private boolean isInternalLink(String url, String baseUrl) {
        try {
            URL nextUrl = new URL(url);
            URL base = new URL(baseUrl);

            String nextHost = nextUrl.getHost().replaceAll("^(http://|https://|www\\.)", "");
            String baseHost = base.getHost().replaceAll("^(http://|https://|www\\.)", "");

            return nextHost.contains(baseHost);
        } catch (MalformedURLException e) {
            log.error("Ошибка при разборе URL: {}", e.getMessage());
            return false;
        }
    }

    private void updateSiteStatus(SiteEntity siteEntity) {
        log.info("Начало обновления статуса сайта: {}", siteEntity.getUrl());
        siteEntity.setStatus(SiteStatus.INDEXED.name());
        siteEntity.setStatusTime(LocalDateTime.now());
        try {
            siteRepository.save(siteEntity);
            log.info("Завершение обновления статуса сайта: {}", siteEntity.getUrl());
        } catch (Exception e) {
            log.error("Ошибка при обновлении статуса сайта {}: {}", siteEntity.getUrl(), e.getMessage());
        }
    }

    private void handleIndexingError(SiteEntity indexedSite, IOException e) {
        String errorMessage = "Ошибка при индексации сайта " + indexedSite.getUrl() + ": " + e.getMessage();
        log.error(errorMessage, e);
        indexedSite.setStatus(SiteStatus.FAILED.name());
        indexedSite.setLastError("Ошибка при попытке получения содержимого сайта: " + e.getMessage());
        try {
            siteRepository.save(indexedSite);
        } catch (Exception ex) {
            String saveError = "Ошибка при сохранении статуса ошибки индексации сайта " + indexedSite.getUrl() + ": " + ex.getMessage();
            log.error(saveError, ex);
        }
    }
}
