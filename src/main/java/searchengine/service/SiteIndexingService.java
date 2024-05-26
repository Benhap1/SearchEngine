package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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

    // Кэш проверки существования URL страниц
    private final ConcurrentHashMap<String, Boolean> pageUrlCache = new ConcurrentHashMap<>();

    private AtomicInteger remainingPages; // Атомарная переменная для отслеживания оставшихся страниц

    @Transactional
    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайтов: {}", sites);
        remainingPages = new AtomicInteger(sites.size()); // Инициализируем счетчик

        ForkJoinPool forkJoinPool = new ForkJoinPool(); // Создаем ForkJoinPool

        forkJoinPool.submit(() -> sites.parallelStream().forEach(this::indexSite)); // Используем ForkJoinPool для выполнения задач

        try {
            forkJoinPool.shutdown();
            forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            // Очистка кэша после завершения всех задач
            clearCache();

        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        log.info("Конец индексации сайтов: {}", sites);
    }

    private void clearCache() {
        pageUrlCache.clear();
        log.info("Кэш страниц очищен.");
    }

    private void indexSite(Site site) {
        log.info("Начало индексации сайта: {}", site.getUrl());

        // Устанавливаем статус индексации для сайта
        updateSiteIndexingStatus(site);

        // Получаем или создаем запись о сайте
        SiteEntity indexedSite = siteRepository.findByUrl(site.getUrl())
                .orElseThrow(() -> new RuntimeException("Не удалось получить запись для сайта: " + site.getUrl()));

        // Производим индексацию страниц
        indexPages(site.getUrl(), indexedSite);

        // Обновляем статус на INDEXED после завершения индексации
        updateSiteStatus(indexedSite);

        log.info("Завершение индексации сайта: {}", site.getUrl());
    }

    private void updateSiteIndexingStatus(Site site) {
        log.info("Установка статуса индексации для сайта: {}", site.getUrl());

        // Удаляем существующие записи страниц для сайта
        Optional<SiteEntity> existingSite = siteRepository.findByUrl(site.getUrl());
        existingSite.ifPresent(siteEntity -> {
            List<PageEntity> existingPages = pageRepository.findBySite(siteEntity);
            if (!existingPages.isEmpty()) {
                log.info("Удаление существующих записей страниц для сайта: {}", site.getUrl());
                pageRepository.deleteAll(existingPages);
            }
        });

        // Удаляем существующую запись о сайте, если она есть
        existingSite.ifPresent(siteRepository::delete);

        // Создаем новую запись о сайте с указанным статусом и временем
        SiteEntity indexedSite = new SiteEntity();
        indexedSite.setUrl(site.getUrl());
        indexedSite.setName(site.getName());
        indexedSite.setStatus(SiteStatus.INDEXING.name());
        indexedSite.setStatusTime(LocalDateTime.now());
        try {
            siteRepository.save(indexedSite);
            log.info("Создана запись для сайта: {}", site.getUrl());
        } catch (Exception e) {
            log.error("Ошибка при создании записи для сайта {}: {}", site.getUrl(), e.getMessage());
            return;
        }

        log.info("Статус индексации для сайта установлен: {}", site.getUrl());
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

        ForkJoinPool forkJoinPool = new ForkJoinPool();

        // Передаем задачи индексации каждой ссылки в ForkJoinPool
        links.forEach(link -> {
            String nextUrl = link.absUrl("href");
            if (visitedUrls.putIfAbsent(nextUrl, true) == null && isInternalLink(nextUrl, siteEntity.getUrl())) {
                forkJoinPool.submit(() -> {
                    try {
                        Document nextDocument = Jsoup.connect(nextUrl).get();
                        visitPage(nextDocument, nextUrl, siteEntity, visitedUrls);
                    } catch (IOException e) {
                        log.error("Ошибка при получении страницы {}: {}", nextUrl, e.getMessage());
                    }
                });
            }
        });

        try {
            forkJoinPool.shutdown();
            forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения индексации страниц: {}", e.getMessage());
            Thread.currentThread().interrupt();
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
