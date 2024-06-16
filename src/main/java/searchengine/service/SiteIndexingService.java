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

    // Кэш проверки существования URL страниц с использованием Caffeine
    private final Cache<String, Boolean> pageUrlCache = Caffeine.newBuilder()
            .maximumSize(600) // Максимальное количество элементов в кэше
            .expireAfterAccess(10, TimeUnit.MINUTES) // Время жизни элемента в кэше после последнего доступа
            .build();

    // Кэш лемм с LRU-очисткой
    private final Cache<String, LemmaEntity> lemmaCache = Caffeine.newBuilder()
            .maximumSize(10000) // Размер кэша
            .expireAfterAccess(10, TimeUnit.MINUTES) // Время жизни элемента в кэше после последнего доступа
            .build();

    private volatile boolean stopRequested = false;


    @Value("${indexing-settings.fork-join-pool.parallelism}")
    private int parallelism;


    public void indexSites(List<Site> sites) {

        log.info("Начало индексации сайтов: {}", sites);

        stopRequested = false;


        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);
        try {
            forkJoinPool.submit(() -> sites.parallelStream().forEach(this::indexSite));

            forkJoinPool.shutdown();
            boolean terminated = forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            if (!terminated) {
                log.warn("ForkJoinPool не завершился в указанный срок");
            }
            if (stopRequested) {
                log.info("Индексация была остановлена пользователем.");
            }
        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
        }
        // Всегда очищаем кэш после завершения индексации
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

        // Обновляем статус индексации сайта
        updateSiteIndexingStatus(site);

        SiteEntity indexedSite = siteRepository.findByUrl(site.getUrl())
                .orElseThrow(() -> new RuntimeException("Не удалось получить запись для сайта: " + site.getUrl()));

        indexPages(site.getUrl(), indexedSite);

        updateSiteStatus(indexedSite);

        log.info("Завершение индексации сайта: {}", site.getUrl());


    }

    @Transactional
    protected void updateSiteIndexingStatus(Site site) {
        log.info("Установка статуса индексации для сайта: {}", site.getUrl());

        // Проверяем наличие записей в таблицах и удаляем их, если они существуют
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
            handleIndexingError(indexedSite, e);
        } finally {
            log.info("Конец индексации страниц сайта: {}", baseUrl);
        }
    }

    private void visitPage(Document document, String url, SiteEntity siteEntity, ConcurrentHashMap<String, Boolean> visitedUrls) {
        if (stopRequested) return;
        log.info("Начало обработки страницы: {}", url);
        visitedUrls.putIfAbsent(url, true);

        if (pageUrlCache.getIfPresent(url) != null) {
            log.info("Страница уже была обработана: {}", url);
            return;
        }
        pageUrlCache.put(url, true);

        if (isFileUrl(url)) {
            log.info("Пропуск файла: {}", url);
            return;
        }

        PageEntity pageEntity = createPageEntity(document, url, siteEntity);
        if (pageEntity == null) {
            log.error("Не удалось создать запись для страницы: {}", url);
            return;
        }

        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
        saveLemmasAndIndices(siteEntity, pageEntity, lemmas);
        extractLinksAndIndexPages(document, siteEntity, visitedUrls);

        log.info("Завершение обработки страницы: {}", url);
    }


    private boolean isFileUrl(String url) {
        return url.endsWith(".pdf") || url.endsWith(".png") || url.endsWith(".jpg");
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
        if (stopRequested) return;
        log.info("Начало извлечения ссылок и индексации страниц: {}", document.baseUri());
        Elements links = document.select("a[href]");

        ForkJoinPool forkJoinPool = new ForkJoinPool(parallelism);
        try {
            links.forEach(link -> {
                String nextUrl = link.absUrl("href");
                if (visitedUrls.putIfAbsent(nextUrl, true) == null && isInternalLink(nextUrl, siteEntity.getUrl())) {
                    forkJoinPool.submit(() -> {
                        if (stopRequested) {
                            log.info("Индексация остановлена пользователем.");
                            return;
                        }
                        try {
                            Document nextDocument = Jsoup.connect(nextUrl).get();
                            if (stopRequested) {
                                log.info("Индексация остановлена пользователем.");
                                return;
                            }
                            visitPage(nextDocument, nextUrl, siteEntity, visitedUrls);
                        } catch (IOException e) {
                            log.error("Ошибка при получении страницы {}: {}", nextUrl, e.getMessage());
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
            log.error("Ошибка при ожидании завершения индексации страниц: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
        }

        log.info("Завершение извлечения ссылок и индексации страниц: {}", document.baseUri());
    }


    public LemmaEntity getOrCreateLemma(String lemma, SiteEntity site) {
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
    public void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) {
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

    public void stopIndexing() {
        stopRequested = true;

        siteRepository.findAll().forEach(siteEntity -> {
            if (siteEntity.getStatus().equals(SiteStatus.INDEXING.name())) {
                siteEntity.setStatus(SiteStatus.FAILED.name());
                siteEntity.setStatusTime(LocalDateTime.now());
                siteEntity.setLastError("Indexing stopped by user");
                siteRepository.save(siteEntity);
                log.info("Статус сайта {} установлен в FAILED", siteEntity.getUrl());
            }
        });
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


    @Transactional
    protected void updateSiteStatus(SiteEntity siteEntity) {
        if (stopRequested) {
            log.info("Индексация остановлена пользователем.");
            siteEntity.setStatus(SiteStatus.FAILED.name());
            siteEntity.setStatusTime(LocalDateTime.now());
            siteEntity.setLastError("Индексация прервана пользователем!");
            siteRepository.save(siteEntity);
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

            // Сохраняем или обновляем страницу
            pageRepository.save(pageEntity);

            // Собираем леммы из содержимого страницы
            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());

            // Сохраняем леммы и индексы
            saveLemmasAndIndices(siteEntity, pageEntity, lemmas);

            log.info("Страница {} успешно индексирована", url);
        } catch (IOException e) {
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage());
        }
    }


    public SearchResults search(String query, String site, int offset, int limit) {
        log.info("Выполнение поиска для запроса: '{}', сайт: '{}', смещение: {}, лимит: {}", query, site, offset, limit);
        List<SearchResultDto> allResults = new ArrayList<>();

        // Если site не указан или пуст, выполнить поиск по всем сайтам
        if (site == null || site.isEmpty()) {
            for (Site currentSite : sitesList.getSites()) {
                List<SearchResultDto> siteResults = performSearch(query, currentSite.getUrl());
                allResults.addAll(siteResults);
            }
        } else {
            // Иначе выполнить поиск по указанному сайту
            List<SearchResultDto> siteResults = performSearch(query, site);
            allResults.addAll(siteResults);
        }

        allResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());

        // Пагинация
        int startIndex = Math.min(offset, allResults.size());
        int endIndex = Math.min(offset + limit, allResults.size());
        List<SearchResultDto> paginatedResults = allResults.subList(startIndex, endIndex);

        log.info("Завершение поиска для запроса: '{}', сайт: '{}', смещение: {}, лимит: {}", query, site, offset, limit);
        return new SearchResults(true, allResults.size(), paginatedResults);
    }




    private List<String> sortLemmasByFrequency(Set<String> lemmas) {
        // Сначала создадим карту для хранения частоты встречаемости лемм
        Map<String, Integer> lemmaFrequencyMap = new HashMap<>();

        // Заполняем карту частотами встречаемости для каждой леммы
        for (String lemma : lemmas) {
            int frequency = lemmaRepository.countByLemma(lemma);
            lemmaFrequencyMap.put(lemma, frequency);
        }

        // Сортируем леммы по частоте встречаемости
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


    private List<SearchResultDto> performSearch(String query, String site) {
        Set<String> lemmas = lemmaFinder.getLemmaSet(query);
        double maxAllowedFrequencyPercentage = 0.5;
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
            searchResults.add(createSearchResult(page, relevance, query));
        }

        for (SearchResultDto result : searchResults) {
            result.setRelevance(result.getRelevance() / maxRelevance);
        }

        // Сортировка результатов по значимости
        searchResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());

        return searchResults;
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


    private SearchResultDto createSearchResult(PageEntity page, double relevance, String query) {
        SearchResultDto result = new SearchResultDto();
        result.setSite(page.getSite().getUrl());
        result.setSiteName(page.getSite().getName());
        result.setUri(page.getPath());
        result.setTitle(extractTitle(page.getContent()));
        result.setSnippet(createSnippet(page.getContent(), query)); // Используем query
        result.setRelevance(relevance);
        System.out.println("Created SearchResultDto: " + result); // Добавляем вывод для отладки
        return result;
    }


    private String extractTitle(String content) {
        Document document = Jsoup.parse(content);
        return document.title();
    }


    private String createSnippet(String content, String query) {
        // Найти леммы запроса
        Set<String> queryLemmas = lemmaFinder.getLemmaSet(query);

        // Создание коллекции для связывания слов контента с их леммами
        Map<String, String> wordToLemmaMap = new HashMap<>();
        String[] words = content.split("\\s+");
        for (String word : words) {
            List<String> lemmas = lemmaFinder.getLemmaSet(word).stream().toList();
            for (String lemma : lemmas) {
                wordToLemmaMap.put(word.toLowerCase(), lemma);
            }
        }

        // Разделить контент на предложения
        String[] sentences = content.split("(?<=[.!?])\\s+");

        // Инициализация переменных для хранения лучшего сниппета
        String bestSnippet = "";
        int maxMatchedWords = 0;
        boolean snippetFound = false;

        // Перебор предложений для поиска лучшего сниппета
        for (String sentence : sentences) {
            // Очистить от HTML тегов
            String cleanSentence = Jsoup.parse(sentence).text();

            // Найти леммы в предложении
            Set<String> sentenceLemmas = lemmaFinder.getLemmaSet(cleanSentence);

            // Подсчитать количество ключевых слов из запроса, найденных в текущем предложении
            int matchedWordsCount = 0;
            for (String lemma : queryLemmas) {
                if (sentenceLemmas.contains(lemma)) {
                    matchedWordsCount++;
                }
            }

            // Если текущее предложение содержит больше ключевых слов, чем предыдущие, обновить лучший сниппет
            if (matchedWordsCount > maxMatchedWords) {
                // Найти первое ключевое слово в предложении
                String[] sentenceWords = cleanSentence.split("\\s+");
                int startIndex = -1;
                for (int i = 0; i < sentenceWords.length; i++) {
                    String word = sentenceWords[i];
                    String lemma = wordToLemmaMap.getOrDefault(word.toLowerCase(), word);
                    if (queryLemmas.contains(lemma)) {
                        startIndex = i;
                        break;
                    }
                }

                // Формирование сниппета от первого найденного ключевого слова
                if (startIndex != -1) {
                    // Начальная и конечная позиции сниппета
                    int snippetStart = Math.max(0, startIndex - 150);
                    int snippetEnd = Math.min(sentenceWords.length - 1, startIndex + 150);

                    StringBuilder snippetBuilder = new StringBuilder();
                    int snippetLength = 0;
                    boolean previousWordWasHighlighted = false;

                    for (int i = snippetStart; i <= snippetEnd; i++) {
                        String snippetWord = sentenceWords[i];
                        String lemma = wordToLemmaMap.getOrDefault(snippetWord.toLowerCase(), snippetWord);

                        boolean highlightWord = queryLemmas.contains(lemma);

                        // Проверка текущей длины сниппета
                        if (snippetLength + snippetWord.length() + 7 > 300) { // 7 - длина тегов <b> и </b>
                            break; // Прервать если достигли максимальной длины сниппета
                        }

                        if (highlightWord) {
                            if (!previousWordWasHighlighted) {
                                snippetBuilder.append("<b>");
                            }
                            snippetBuilder.append(snippetWord).append(" ");
                            previousWordWasHighlighted = true;
                        } else {
                            if (previousWordWasHighlighted) {
                                snippetBuilder.append("</b>");
                            }
                            snippetBuilder.append(snippetWord).append(" ");
                            previousWordWasHighlighted = false;
                        }

                        // Обновление текущей длины сниппета
                        snippetLength += snippetWord.length() + 1; // +1 для пробела между словами
                    }

                    // Завершение открытого тега <b> в случае, если последнее слово было выделено
                    if (previousWordWasHighlighted) {
                        snippetBuilder.append("</b>");
                    }

                    bestSnippet = snippetBuilder.toString().trim();
                    maxMatchedWords = matchedWordsCount;
                    snippetFound = true;
                }
            }
        }

        // Если не удалось найти подходящий сниппет, вернуть первые 300 символов контента
        if (!snippetFound) {
            bestSnippet = content.substring(0, Math.min(content.length(), 300));
        }

        return bestSnippet; // Вернуть сформированный сниппет
    }
}

