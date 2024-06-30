package searchengine.service;

import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import searchengine.util.GlobalErrorsHandler;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteIndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final GlobalErrorsHandler globalErrorsHandler;
    private final CacheManagement cacheManagement;
    private volatile boolean stopRequested = false;
    private boolean indexingInProgress = false;
    private final Object lock = new Object();
    private final UrlNormalizer urlNormalizer;

    @Value("${indexing-settings.fork-join-pool.parallelism}")
    private int parallelism;


    public String startIndexing(List<Site> sites) {
        synchronized (lock) {
            if (indexingInProgress) {
                return "{\"result\": false, \"error\": \"Индексация уже запущена\"}";
            }
            indexingInProgress = true;
        }

        new Thread(() -> {
            try {
                indexSites(sites);
            } finally {
                synchronized (lock) {
                    indexingInProgress = false;
                }
            }
        }).start();

        return "{\"result\": true}";
    }

    public String stopIndex() {
        synchronized (lock) {
            if (!indexingInProgress) {
                return "{\"result\": false, \"error\": \"Индексация не запущена\"}";
            }
            stopRequested = true;
            indexingInProgress = false;
        }
        return "{\"result\": true}";
    }

    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайтов: {}", sites);

        List<String> clearedErrors = globalErrorsHandler.getAllErrorsAndClear();
        if (!clearedErrors.isEmpty()) {
            log.info("Ошибки, очищенные перед началом индексации: {}", clearedErrors);
        }

        stopRequested = false;

        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);

        try {
            forkJoinPool.submit(() -> sites.parallelStream().forEach(this::indexSite));

            forkJoinPool.shutdown();
            boolean terminated = forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            if (!terminated) {
                globalErrorsHandler.addError("ForkJoinPool не завершился в указанный срок");
            }
            if (stopRequested) {
                globalErrorsHandler.addError("Индексация была остановлена пользователем.");
            }
        } catch (InterruptedException e) {
            globalErrorsHandler.addError("Ошибка при ожидании завершения индексации сайтов: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                globalErrorsHandler.addError("Принудительное завершение незавершенных задач в ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
            synchronized (lock) {
                indexingInProgress = false;
            }
        }

        List<String> errorsAfterIndexing = globalErrorsHandler.getErrors();
        if (!errorsAfterIndexing.isEmpty()) {
            log.info("Ошибки, возникшие во время индексации: {}", errorsAfterIndexing);
        }

        cacheManagement.clearCache();

        log.info("Конец индексации сайтов: {}", sites);
    }


    @Transactional
    protected void indexSite(Site site) {
        log.info("Начало индексации сайта: {}", site.getUrl());
        try {
            updateSiteIndexingStatus(site);
            SiteEntity indexedSite = siteRepository.findByUrl(site.getUrl())
                    .orElseThrow(() -> new RuntimeException("Не удалось получить запись для сайта: " + site.getUrl()));
            indexPages(site.getUrl(), indexedSite);
            updateSiteStatus(indexedSite);
        } catch (Exception e) {
            String errorMessage = "Ошибка при индексации сайта " + site.getUrl() + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage, e);
        }

        log.info("Завершение индексации сайта: {}", site.getUrl());
    }

    @Transactional
    protected void updateSiteIndexingStatus(Site site) {
        log.info("Установка статуса индексации для сайта: {}", site.getUrl());
        try {
            if (indexRepository.count() > 0) {
                log.info("Таблица indexx не пуста, удаляем данные");
                indexRepository.deleteAllInBatch();
            } else {
                log.info("Таблица indexx пуста, удаление пропущено");
            }

            if (lemmaRepository.count() > 0) {
                log.info("Таблица lemma не пуста, удаляем данные");
                lemmaRepository.deleteAllInBatch();
            } else {
                log.info("Таблица lemma пуста, удаление пропущено");
            }

            if (pageRepository.count() > 0) {
                log.info("Таблица page не пуста, удаляем данные");
                pageRepository.deleteAllInBatch();
            } else {
                log.info("Таблица page пуста, удаление пропущено");
            }

            if (siteRepository.count() > 0) {
                log.info("Таблица site не пуста, удаляем данные");
                siteRepository.deleteAllInBatch();
            } else {
                log.info("Таблица site пуста, удаление пропущено");
            }

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
        } catch (Exception e) {
            String errorMessage = "Ошибка при установке статуса индексации для сайта " + site.getUrl() + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage, e);
        }
    }


    private void indexPages(String baseUrl, SiteEntity indexedSite) {
        if (stopRequested) return;
        log.info("Начало индексации страниц сайта: {}", baseUrl);
        try {
            Document document = Jsoup.connect(baseUrl).get();
            String baseUri = document.baseUri();

            ConcurrentHashMap<String, Boolean> visitedUrls = new ConcurrentHashMap<>();
            visitPage(document, baseUri, indexedSite, visitedUrls);
        } catch (IOException e) {
            String errorMessage = "Ошибка при индексации страниц сайта " + baseUrl + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage, e);
        } finally {
            log.info("Конец индексации страниц сайта: {}", baseUrl);
        }
    }


    public void visitPage(Document document, String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) return;

        String normalizedUrl = urlNormalizer.normalizeUrl(url);
        log.info("Начало обработки страницы: {}", normalizedUrl);
        visitedUrls.putIfAbsent(normalizedUrl, true);

        if (cacheManagement.pageUrlCache.getIfPresent(normalizedUrl) != null) {
            log.info("Страница уже была обработана: {}", normalizedUrl);
            return;
        }
        cacheManagement.pageUrlCache.put(normalizedUrl, true);

        if (isFileUrl(normalizedUrl)) {
            log.info("Пропуск файла: {}", normalizedUrl);
            return;
        }

        PageEntity pageEntity = createPageEntity(document, normalizedUrl, siteEntity);
        if (pageEntity == null) {
            log.error("Не удалось создать запись для страницы: {}", normalizedUrl);
            return;
        }

        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
        saveLemmasAndIndices(siteEntity, pageEntity, lemmas);
        extractLinksAndIndexPages(document, siteEntity, visitedUrls);

        log.info("Завершение обработки страницы: {}", normalizedUrl);
    }





    private boolean isFileUrl(String url) {
        return url.endsWith(".pdf") || url.endsWith(".png") || url.endsWith(".jpg");
    }


    private PageEntity createPageEntity(Document document, String url, SiteEntity siteEntity) {
        log.info("Начало создания записи страницы: {}", url);

        String path;
        try {
            URL parsedUrl = new URL(url);
            path = parsedUrl.getPath().replaceAll("/{2,}", "/").replaceAll("/$", "");

            if (path.isEmpty()) {
                path = "/";
            }
        } catch (MalformedURLException e) {
            String errorMessage = "Ошибка при разборе URL " + url + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
            return null;
        }

        Optional<PageEntity> existingPage;
        try {
            existingPage = pageRepository.findBySiteAndPath(siteEntity, path);
            if (existingPage.isPresent()) {
                log.info("Запись для страницы уже существует: {}", url);
                return existingPage.get();
            }

            PageEntity pageEntity = new PageEntity();
            pageEntity.setSite(siteEntity);
            pageEntity.setPath(path);

            int statusCode;
            try {
                statusCode = Jsoup.connect(url).execute().statusCode();
                pageEntity.setCode(statusCode);
                pageEntity.setContent(document.outerHtml());
                pageRepository.save(pageEntity);
                log.info("Запись страницы успешно сохранена: {}", url);
                return pageEntity;
            } catch (IOException e) {
                String errorMessage = "Ошибка при получении статуса страницы " + url + ": " + e.getMessage();
                globalErrorsHandler.addError(errorMessage);
                log.error(errorMessage);
            } catch (Exception e) {
                String errorMessage = "Ошибка при сохранении содержимого страницы " + url + ": " + e.getMessage();
                globalErrorsHandler.addError(errorMessage);
                log.error(errorMessage);
            } finally {
                log.info("Завершение создания записи страницы: {}", url);
            }

        } catch (Exception e) {
            String errorMessage = "Ошибка при поиске записи страницы для сайта " + siteEntity.getUrl() + ": " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
        }

        return null;
    }


    private void extractLinksAndIndexPages(Document document, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) return;
        log.info("Начало извлечения ссылок и индексации страниц: {}", document.baseUri());
        Elements links = document.select("a[href]");

        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);
        try {
            links.forEach(link -> {
                String nextUrl = link.absUrl("href");
                String normalizedNextUrl = urlNormalizer.normalizeUrl(nextUrl);
                if (visitedUrls.putIfAbsent(normalizedNextUrl, true) == null && isInternalLink(normalizedNextUrl, siteEntity.getUrl())) {
                    forkJoinPool.submit(() -> {
                        if (stopRequested) {
                            log.info("Индексация остановлена пользователем.");
                            return;
                        }
                        try {
                            Document nextDocument = Jsoup.connect(normalizedNextUrl).get();
                            if (stopRequested) {
                                log.info("Индексация остановлена пользователем.");
                                return;
                            }
                            visitPage(nextDocument, normalizedNextUrl, siteEntity, visitedUrls);
                        } catch (IOException e) {
                            String errorMessage = "Ошибка при получении страницы " + normalizedNextUrl + ": " + e.getMessage();
                            globalErrorsHandler.addError(errorMessage);
                            log.error(errorMessage);
                        }
                    });
                }
            });

            forkJoinPool.shutdown();
            boolean terminated = forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            if (!terminated) {
                log.warn("ForkJoinPool не завершился в указанный срок");
            }

        } catch (InterruptedException e) {
            String errorMessage = "Ошибка при ожидании завершения индексации страниц: " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
        }

        log.info("Завершение извлечения ссылок и индексации страниц: {}", document.baseUri());
    }



    private LemmaEntity getOrCreateLemma(String lemma, SiteEntity site) {
        return cacheManagement.lemmaCache.get(lemma, key -> {
            LemmaEntity existingLemma = lemmaRepository.findByLemmaAndSite(key, site).orElse(null);
            if (existingLemma != null) {
                return existingLemma;
            } else {
                LemmaEntity newLemma = new LemmaEntity();
                newLemma.setLemma(lemma);
                newLemma.setSite(site);
                newLemma.setFrequency(1);
                return newLemma;
            }

        });
    }


    @Synchronized
    protected void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) { //изменил паблик на прайвет
        if (stopRequested) return;
        List<LemmaEntity> lemmaEntities = new ArrayList<>();
        List<IndexEntity> indexEntities = new ArrayList<>();

        lemmas.forEach((lemma, frequency) -> {
            LemmaEntity lemmaEntity = getOrCreateLemma(lemma, siteEntity);
            lemmaEntity.setFrequency(lemmaEntity.getFrequency() + frequency);
            lemmaEntities.add(lemmaEntity);

            IndexEntity indexEntity = new IndexEntity();
            indexEntity.setPage(pageEntity);
            indexEntity.setLemma(lemmaEntity);
            indexEntity.setRank(Float.valueOf(frequency));
            indexEntities.add(indexEntity);
        });

        lemmaRepository.saveAll(lemmaEntities);
        indexRepository.saveAll(indexEntities);
    }


    private boolean isInternalLink(String url, String baseUrl) {
        String normalizedUrl = urlNormalizer.normalizeUrl(url);
        String normalizedBaseUrl = urlNormalizer.normalizeUrl(baseUrl);

        try {
            URL nextUrl = new URL(normalizedUrl);
            URL base = new URL(normalizedBaseUrl);

            String nextHost = nextUrl.getHost().replaceAll("^(http://|https://|www\\.)", "");
            String baseHost = base.getHost().replaceAll("^(http://|https://|www\\.)", "");

            return nextHost.contains(baseHost);
        } catch (MalformedURLException e) {
            String errorMessage = "Ошибка при разборе URL: " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
            return false;
        }
    }


    @Transactional
    protected void updateSiteStatus(SiteEntity siteEntity) {
        if (stopRequested) {
            log.info("Индексация остановлена пользователем.");
            siteEntity.setStatus(SiteStatus.FAILED.name());
            siteEntity.setStatusTime(LocalDateTime.now());
            siteEntity.setLastError("Индексация прервана пользователем!");
        } else {
            log.info("Начало обновления статуса сайта: {}", siteEntity.getUrl());
            siteEntity.setStatus(SiteStatus.INDEXED.name());
            siteEntity.setStatusTime(LocalDateTime.now());
            siteEntity.setLastError(null);
        }

        try {
            siteRepository.save(siteEntity);
            log.info("Завершение полной индексации и лемматизации сайта: {}", siteEntity.getUrl());
        } catch (Exception e) {
            String errorMessage = String.format("Ошибка при обновлении статуса сайта %s: %s", siteEntity.getUrl(), e.getMessage());
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
        }
    }
}

