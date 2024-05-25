package searchengine.service;

import lombok.Getter;
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


    private AtomicInteger remainingPages; // Атомарная переменная для отслеживания оставшихся страниц


    @Transactional
    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайта метода indexSites: {}", sites);
        ExecutorService executorService = Executors.newFixedThreadPool(sites.size());
        remainingPages = new AtomicInteger(sites.size()); // Инициализируем счетчик

        try {
            sites.forEach(site -> executorService.submit(() -> indexSite(site)));
            executorService.shutdown();
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!executorService.isTerminated()) {
                executorService.shutdownNow();
            }
        }

        log.info("Конец индексации сайта метода indexSites: {}", sites);
    }

    @Transactional
    public void indexSite(Site site) {
        log.info("Начало индексации сайта indexSite: {}", site.getUrl());
        SiteEntity indexedSite = prepareSiteEntity(site); // Получаем или создаем запись о сайте
        if (indexedSite == null) {
            log.error("Не удалось создать запись для сайта: {}", site.getUrl());
            return;
        }
        String currentStatus = indexedSite.getStatus();
        log.info("Статус сайта до обновления: {}", indexedSite.getStatus());
        if (currentStatus.equals(SiteStatus.INDEXED.name()) || currentStatus.equals(SiteStatus.FAILED.name()) || currentStatus.equals(SiteStatus.INDEXING.name())) {

            // Если сайт уже проиндексирован или индексация завершилась с ошибкой, устанавливаем новое время начала индексации
            indexedSite.setStatus(SiteStatus.INDEXING.name());
            indexedSite.setStatusTime(LocalDateTime.now());

            // Сохраняем обновленный статус и время в базе данных
            siteRepository.save(indexedSite);

            log.info("Статус сайта после обновления: {}", indexedSite.getStatus());
        }
// Проверяем, есть ли уже записи страниц для данного сайта
        List<PageEntity> existingPages = pageRepository.findBySite(indexedSite);
        if (!existingPages.isEmpty()) {
            log.info("Удаление существующих записей страниц для сайта: {}", indexedSite.getUrl());
            pageRepository.deleteAll(existingPages); // Удаляем существующие записи страниц
        }
        indexPages(site.getUrl(), indexedSite); // Производим индексацию страниц
        updateSiteStatus(indexedSite); // Обновляем статус на INDEXED после завершения индексации
        log.info("Завершение индексации сайта indexSite: {}", site.getUrl());
    }

    @Transactional
    public void stopIndexing() {
        // Останавливаем индексацию для всех сайтов, находящихся в процессе индексации
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
        Optional<SiteEntity> existingSite = siteRepository.findByUrl(site.getUrl());
        SiteEntity indexedSite;
        if (existingSite.isPresent()) {
            // Если сайт уже есть в базе, возвращаем существующую запись
            indexedSite = existingSite.get();
        } else {
            // Если сайта нет в базе, создаем новую запись
            indexedSite = new SiteEntity();
            indexedSite.setUrl(site.getUrl());
            indexedSite.setName(site.getName());
            indexedSite.setStatus(SiteStatus.INDEXING.name()); // Устанавливаем статус INDEXING
            indexedSite.setStatusTime(LocalDateTime.now()); // Устанавливаем время начала индексации
            try {
                siteRepository.save(indexedSite);
                log.info("Создана запись для сайта: {}", site.getUrl());
            } catch (Exception e) {
                log.error("Ошибка при создании записи для сайта {}: {}", site.getUrl(), e.getMessage());
                return null;
            } finally {
                log.info("Завершение подготовки данных сайта: {}", site.getUrl());
            }
        }
        return indexedSite;
    }

    private void visitPage(Document document, String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        log.info("Начало обработки страницы: {}", url);
        visitedUrls.putIfAbsent(url, true); // Добавляем URL-адрес, если его еще нет в мапе

        PageEntity pageEntity = createPageEntity(document, url, siteEntity);
        if (pageEntity == null) {
            log.error("Не удалось создать запись для страницы: {}", url);
            return;
        }

        extractLinksAndIndexPages(document, siteEntity, visitedUrls);


        // После обработки всех страниц уменьшаем счетчик и логируем завершение, если все страницы обработаны
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

        // Проверка наличия дубликатов
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
        int newUrlsCount = 0; // Счетчик новых URL-адресов
        for (Element link : links) {
            String nextUrl = link.absUrl("href");
            if (visitedUrls.putIfAbsent(nextUrl, true) == null && isInternalLink(nextUrl, siteEntity.getUrl())) {
                ForkJoinPool.commonPool().invoke(new IndexPageTask(nextUrl, siteEntity, visitedUrls));
                newUrlsCount++; // Увеличение счетчика новых URL-адресов
            }
        }
        if (newUrlsCount == 0) {
            log.info("Больше нет новых ссылок для индексации. Прекращение индексации.");
            return; // Прекращение индексации, если нет новых ссылок
        }
        log.info("Завершение извлечения ссылок и индексации страниц: {}", document.baseUri());
    }

    private boolean isInternalLink(String url, String baseUrl) {
        try {
            URL nextUrl = new URL(url);
            URL base = new URL(baseUrl);

            // Получаем хосты (домены) без протокола (http:// или https://)
            String nextHost = nextUrl.getHost().replaceAll("^(http://|https://|www\\.)", "");
            String baseHost = base.getHost().replaceAll("^(http://|https://|www\\.)", "");


            // Проверяем, содержит ли ссылка имя домена базового URL сайта
            return nextHost.contains(baseHost);
        } catch (MalformedURLException e) {
            // Обработка ошибки, если URL некорректный
            log.error("Ошибка при разборе URL: {}", e.getMessage());
            return false;
        }
    }



    private void updateSiteStatus(SiteEntity siteEntity) {
        log.info("Начало обновления статуса сайта: {}", siteEntity.getUrl());
        siteEntity.setStatus(SiteStatus.INDEXED.name()); // Устанавливаем новый статус INDEXED
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
        log.error(errorMessage, e); // Логирование ошибки с деталями исключения
        indexedSite.setStatus(SiteStatus.FAILED.name());
        indexedSite.setLastError("Ошибка при попытке получения содержимого сайта: " + e.getMessage());
        try {
            siteRepository.save(indexedSite);
        } catch (Exception ex) {
            String saveError = "Ошибка при сохранении статуса ошибки индексации сайта " + indexedSite.getUrl() + ": " + ex.getMessage();
            log.error(saveError, ex); // Логирование ошибки сохранения с деталями исключения
        }
    }


    private class IndexPageTask extends RecursiveAction {

        private final String url;
        private final SiteEntity siteEntity;
        private final ConcurrentHashMap<String, Boolean> visitedUrls;

        public IndexPageTask(String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
            this.url = url;
            this.siteEntity = siteEntity;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected void compute() {
            log.info("Начало обработки страницы в фоновом потоке: {}", url);
            try {
                Document document = Jsoup.connect(url).get();
                String baseUri = document.baseUri();
                visitPage(document, baseUri, siteEntity, visitedUrls);
            } catch (IOException e) {
                log.error("Ошибка при получении страницы {}: {}", url, e.getMessage());
            } finally {
                log.info("Завершение обработки страницы в фоновом потоке: {}", url);
            }
        }
    }
}
