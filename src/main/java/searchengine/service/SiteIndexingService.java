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
import searchengine.config.SitesList;
import searchengine.dto.search.SearchResultDto;
import searchengine.dto.search.SearchResults;
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
import java.util.stream.Collectors;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    private final SitesList sitesList;
    private final GlobalErrorsHandler globalErrorsHandler;

    public final Cache<String, Boolean> pageUrlCache = Caffeine.newBuilder()
            .maximumSize(600) // Максимальное количество элементов в кэше
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    private final Cache<String, LemmaEntity> lemmaCache = Caffeine.newBuilder()
            .maximumSize(10000) // Размер кэша
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    private volatile boolean stopRequested = false;
    private boolean indexingInProgress = false;
    private final Object lock = new Object();

    @Value("${indexing-settings.fork-join-pool.parallelism}")
    private int parallelism;

    public boolean startIndexing(List<Site> sites) {
        synchronized (lock) {
            if (indexingInProgress) {
                return false;
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

        return true;
    }

    public boolean stopIndex() {
        synchronized (lock) {
            if (!indexingInProgress) {
                return false;
            }
            stopRequested = true;
            indexingInProgress = false;
        }
        return true;
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
                log.warn("ForkJoinPool не завершился в указанный срок");
            }
            if (stopRequested) {
                globalErrorsHandler.addError("Индексация была остановлена пользователем.");
                log.info("Индексация была остановлена пользователем.");
            }
        } catch (InterruptedException e) {
            globalErrorsHandler.addError("Ошибка при ожидании завершения индексации сайтов: " + e.getMessage());
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                globalErrorsHandler.addError("Принудительное завершение незавершенных задач в ForkJoinPool");
                log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
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

        clearCache();

        log.info("Конец индексации сайтов: {}", sites);
    }


    private void clearCache() {
        pageUrlCache.invalidateAll();
        lemmaCache.invalidateAll();  // Добавить очистку кэша лемм
        log.info("Кэш страниц и лемм очищен.");
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


    private String normalizeUrl(String url) {
        try {
            URL uri = new URL(url);
            String path = uri.getPath().replaceAll("/{2,}", "/").replaceAll("/$", "");
            if (path.isEmpty()) {
                path = "/";
            }
            return new URL(uri.getProtocol(), uri.getHost(), path).toString().toLowerCase();
        } catch (MalformedURLException e) {
            String errorMessage = "Ошибка при нормализации URL: " + e.getMessage();
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);

            return url.toLowerCase().replaceAll("/{2,}", "/").replaceAll("/$", ""); // возвращаем нормализованный URL даже при ошибке
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

        String normalizedUrl = normalizeUrl(url);
        log.info("Начало обработки страницы: {}", normalizedUrl);
        visitedUrls.putIfAbsent(normalizedUrl, true);

        if (pageUrlCache.getIfPresent(normalizedUrl) != null) {
            log.info("Страница уже была обработана: {}", normalizedUrl);
            return;
        }
        pageUrlCache.put(normalizedUrl, true);

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
                String normalizedNextUrl = normalizeUrl(nextUrl);
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



    private LemmaEntity getOrCreateLemma(String lemma, SiteEntity site) {  //изменил паблик на прайвет)
        return lemmaCache.get(lemma, key -> {
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
    private void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) { //изменил паблик на прайвет
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
        String normalizedUrl = normalizeUrl(url);
        String normalizedBaseUrl = normalizeUrl(baseUrl);

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


    public boolean indexPage(String url) throws MalformedURLException {
        URL parsedUrl = new URL(url);
        String host = parsedUrl.getHost();

        Optional<SiteEntity> optionalSiteEntity = siteRepository.findByUrlContaining(host);
        if (optionalSiteEntity.isEmpty()) {
            return false;
        }

        SiteEntity siteEntity = optionalSiteEntity.get();
        indexPageEntity(siteEntity, url);

        return true;
    }


    private void indexPageEntity(SiteEntity siteEntity, String url) {
        try {
            Document document = Jsoup.connect(url).get();
            String path = new URL(url).getPath();

            Optional<PageEntity> existingPage = pageRepository.findBySiteAndPath(siteEntity, path);
            PageEntity pageEntity = existingPage.orElse(new PageEntity());

            pageEntity.setSite(siteEntity);
            pageEntity.setPath(path);
            pageEntity.setContent(document.outerHtml());

            int statusCode = Jsoup.connect(url).execute().statusCode();
            pageEntity.setCode(statusCode);

            pageRepository.save(pageEntity);

            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());

            saveLemmasAndIndices(siteEntity, pageEntity, lemmas);

            log.info("Страница {} успешно индексирована", url);
        } catch (IOException e) {
            String errorMessage = String.format("Ошибка при индексации страницы %s: %s", url, e.getMessage());
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
        }
    }



    public SearchResults search(String query, String site, int offset, int limit) {
        log.info("Выполнение поиска для запроса: '{}', сайт: '{}', смещение: {}, лимит: {}", query, site, offset, limit);
        List<SearchResultDto> allResults = new ArrayList<>();

        if (site == null || site.isEmpty()) {
            for (Site currentSite : sitesList.getSites()) {
                List<SearchResultDto> siteResults = performSearch(query, currentSite.getUrl());
                allResults.addAll(siteResults);
            }
        } else {
            List<SearchResultDto> siteResults = performSearch(query, site);
            allResults.addAll(siteResults);
        }

        allResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());

        int startIndex = Math.min(offset, allResults.size());
        int endIndex = Math.min(offset + limit, allResults.size());
        List<SearchResultDto> paginatedResults = allResults.subList(startIndex, endIndex);

        log.info("Завершение поиска для запроса: '{}', сайт: '{}', смещение: {}, лимит: {}", query, site, offset, limit);
        return new SearchResults(true, allResults.size(), paginatedResults);
    }


    private List<SearchResultDto> performSearch(String query, String site) {
        Set<String> lemmas = lemmaFinder.getLemmaSet(query);
        double maxAllowedFrequencyPercentage = 1.0;
        Set<String> filteredLemmas = filterFrequentLemmas(lemmas, maxAllowedFrequencyPercentage);
        List<String> sortedLemmas = sortLemmasByFrequency(filteredLemmas);

        List<PageEntity> pages = findPagesByLemmas(sortedLemmas, site);
        if (pages.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResultDto> searchResults = new ArrayList<>();
        double maxRelevance = 0.0;

        for (PageEntity page : pages) {
            double relevance = calculateRelevance(page, sortedLemmas);
            if (relevance > maxRelevance) {
                maxRelevance = relevance;
            }
            searchResults.add(createSearchResult(page, relevance, sortedLemmas));
        }

        for (SearchResultDto result : searchResults) {
            result.setRelevance(result.getRelevance() / maxRelevance);
        }

        searchResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());

        return searchResults;
    }


    private List<String> sortLemmasByFrequency(Set<String> lemmas) {
        Map<String, Integer> lemmaFrequencyMap = new HashMap<>();
        for (String lemma : lemmas) {
            int frequency = lemmaRepository.countByLemma(lemma);
            lemmaFrequencyMap.put(lemma, frequency);
        }

        return lemmaFrequencyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private Set<String> filterFrequentLemmas(Set<String> lemmas, double maxAllowedFrequencyPercentage) {
        Set<String> filteredLemmas = new HashSet<>();
        int totalPageCount = (int) pageRepository.count();

        for (String lemma : lemmas) {
            int lemmaFrequency = lemmaRepository.countByLemma(lemma);
            double frequencyPercentage = (double) lemmaFrequency / totalPageCount;
            if (frequencyPercentage <= maxAllowedFrequencyPercentage) {
                filteredLemmas.add(lemma);
            }
        }
        return filteredLemmas;
    }


    private List<PageEntity> findPagesByLemmas(List<String> sortedLemmas, String site) {
        return pageRepository.findPagesByLemmasAndSite(sortedLemmas, site, sortedLemmas.size());
    }

    private double calculateRelevance(PageEntity page, List<String> sortedLemmas) {
        List<IndexEntity> indices = indexRepository.findByPageAndLemmas(page, sortedLemmas);
        return indices.stream()
                .mapToDouble(IndexEntity::getRank)
                .sum();
    }


    private SearchResultDto createSearchResult(PageEntity page, double relevance, List<String> sortedLemmas) {
        SearchResultDto result = new SearchResultDto();
        result.setSite(page.getSite().getUrl());
        result.setSiteName(page.getSite().getName());
        result.setUri(page.getPath());
        result.setTitle(extractTitle(page.getContent()));
        result.setSnippet(createSnippet(page.getContent(), sortedLemmas)); // Используем query
        result.setRelevance(relevance);
        System.out.println("Created SearchResultDto: " + result); // Добавляем вывод для отладки
        return result;
    }


    private String extractTitle(String content) {
        Document document = Jsoup.parse(content);
        return document.title();
    }


    private String createSnippet(String content, List<String> sortedLemmas) {
        String cleanContent = Jsoup.parse(content).text();
        cleanContent = cleanContent.replaceAll("[^\\p{IsCyrillic}\\s]", " ");
        String bestSnippet = "";
        for (String lemma : sortedLemmas) {
            int keywordIndex = cleanContent.indexOf(lemma);
            if (keywordIndex != -1) {
                int snippetStart = Math.max(0, keywordIndex - 150);
                int snippetEnd = Math.min(cleanContent.length(), keywordIndex + lemma.length() + 150);
                bestSnippet = cleanContent.substring(snippetStart, snippetEnd);
                bestSnippet = highlightKeywords(bestSnippet, sortedLemmas);

                return bestSnippet;
            }
        }

        if (cleanContent.length() > 300) {
            bestSnippet = cleanContent.substring(0, 300);
        }

        bestSnippet = highlightKeywords(bestSnippet, sortedLemmas);

        return bestSnippet;
    }

    private String highlightKeywords(String snippet, List<String> sortedLemmas) {
        StringBuilder snippetBuilder = new StringBuilder();
        String[] words = snippet.split("\\s+");
        boolean previousWordWasHighlighted = false;

        for (String word : words) {
            List<String> lemmas = lemmaFinder.getLemmaSet(word).stream().toList();
            String lemma = lemmas.isEmpty() ? word : lemmas.get(0); // Use first lemma if available
            boolean highlightWord = sortedLemmas.contains(lemma);

            if (highlightWord) {
                if (!previousWordWasHighlighted) {
                    snippetBuilder.append("<b>");
                }
                snippetBuilder.append(word).append(" ");
                previousWordWasHighlighted = true;
            } else {
                if (previousWordWasHighlighted) {
                    snippetBuilder.append("</b>");
                }
                snippetBuilder.append(word).append(" ");
                previousWordWasHighlighted = false;
            }
        }
        if (previousWordWasHighlighted) {
            snippetBuilder.append("</b>");
        }
        return snippetBuilder.toString().trim();
    }
}

