package searchengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
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

@Slf4j
@Service
public class SiteIndexingService {

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private PageRepository pageRepository;

    @Autowired
    private LemmaRepository lemmaRepository;

    @Autowired
    private IndexRepository indexRepository;

    @Autowired
    private LemmaFinder lemmaFinder;

    @Autowired
    private RedisCommands<String, String> redisCommands;

    @Autowired
    private ObjectMapper objectMapper; // Внедряем ObjectMapper

    // Кэш проверки существования URL страниц
    private final ConcurrentHashMap<String, Boolean> pageUrlCache = new ConcurrentHashMap<>();

    private volatile boolean stopRequested = false;

    @Value("${indexing-settings.fork-join-pool.parallelism}")
    private int parallelism;

    @Transactional
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

            mergeLemmas();

            clearCache();
        } catch (InterruptedException e) {
            log.error("Ошибка при ожидании завершения индексации сайтов: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!forkJoinPool.isTerminated()) {
                log.warn("Принудительное завершение незавершенных задач в ForkJoinPool");
                forkJoinPool.shutdownNow();
            }
        }

        log.info("Конец индексации сайтов: {}", sites);
    }

    private void clearCache() {
        pageUrlCache.clear();
        log.info("Кэш страниц очищен.");
    }

    private void indexSite(Site site) {
        log.info("Начало индексации сайта: {}", site.getUrl());

        updateSiteIndexingStatus(site);

        SiteEntity indexedSite = siteRepository.findByUrl(site.getUrl())
                .orElseThrow(() -> new RuntimeException("Не удалось получить запись для сайта: " + site.getUrl()));

        indexPages(site.getUrl(), indexedSite);


        updateSiteStatus(indexedSite);

        log.info("Завершение индексации сайта: {}", site.getUrl());
    }

    private void updateSiteIndexingStatus(Site site) {
        log.info("Установка статуса индексации для сайта: {}", site.getUrl());
        Optional<SiteEntity> existingSite = siteRepository.findByUrl(site.getUrl());
        existingSite.ifPresent(siteEntity -> {
            List<PageEntity> existingPages = pageRepository.findBySite(siteEntity);
            if (!existingPages.isEmpty()) {
                log.info("Удаление существующих записей страниц для сайта: {}", site.getUrl());
                pageRepository.deleteAll(existingPages);
            }
        });
        existingSite.ifPresent(siteRepository::delete);

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

    public void stopIndexing() {
        stopRequested = true;

        siteRepository.findAll().forEach(siteEntity -> {
            if (siteEntity.getStatus().equals(SiteStatus.INDEXING.name())) {
                siteEntity.setStatus(SiteStatus.FAILED.name());
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
                log.info("Статус сайта {} установлен в FAILED", siteEntity.getUrl());
            }
        });
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

        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
        saveLemmasAndIndices(siteEntity, pageEntity, lemmas);
        extractLinksAndIndexPages(document, siteEntity, visitedUrls);

        log.info("Завершение обработки страницы: {}", url);
    }

    private PageEntity createPageEntity(Document document, String url, SiteEntity siteEntity) {
        if (stopRequested) {
            log.info("Индексация остановлена пользователем.");
            return null;
        }
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
        if (stopRequested) {
            log.info("Индексация остановлена пользователем.");
            siteEntity.setStatus(SiteStatus.FAILED.name());
            siteRepository.save(siteEntity);
        } else {
            log.info("Начало обновления статуса сайта: {}", siteEntity.getUrl());
            siteEntity.setStatus(SiteStatus.INDEXED.name());
            siteEntity.setStatusTime(LocalDateTime.now());
        }

        try {
            siteRepository.save(siteEntity);
            log.info("Завершение обновления статуса сайта: {}", siteEntity.getUrl());
        } catch (Exception e) {
            log.error("Ошибка при обновлении статуса сайта {}: {}", siteEntity.getUrl(), e.getMessage());
        }
    }

    public void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) {
        int batchSize = 5000;
        List<LemmaEntity> lemmaEntities = new ArrayList<>();
        List<IndexEntity> indexEntities = new ArrayList<>();

        int count = 0;
        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            LemmaEntity lemmaEntity = new LemmaEntity();
            lemmaEntity.setLemma(entry.getKey());
            lemmaEntity.setFrequency(1);
            lemmaEntity.setSite(siteEntity);
            lemmaEntities.add(lemmaEntity);

            IndexEntity indexEntity = new IndexEntity();
            indexEntity.setPage(pageEntity);
            indexEntity.setLemma(lemmaEntity);
            indexEntity.setRank(entry.getValue().floatValue());
            indexEntities.add(indexEntity);

            count++;

            if (count % batchSize == 0) {
                // Сохранение лемм и индексов в базу данных
                try {
                    lemmaRepository.saveAll(lemmaEntities);
                    indexRepository.saveAll(indexEntities);
                } catch (Exception e) {
                    log.error("Ошибка при сохранении лемм и индексов: {}", e.getMessage());
                }

                // Очистка списков для следующего пакета
                lemmaEntities.clear();
                indexEntities.clear();
            }
        }

        // Сохранение оставшихся лемм и индексов, если есть
        if (!lemmaEntities.isEmpty()) {
            try {
                lemmaRepository.saveAll(lemmaEntities);
                indexRepository.saveAll(indexEntities);
            } catch (Exception e) {
                log.error("Ошибка при сохранении лемм и индексов: {}", e.getMessage());
            }
        }

    }

    public void mergeLemmas() {
        log.info("Начало объединения дублирующихся лемм.");

        try {
            // Получение всех лемм и индексов
            List<LemmaEntity> allLemmas = lemmaRepository.findAll();
            List<IndexEntity> allIndices = indexRepository.findAll();

            // Подсчет частоты каждой леммы
            Map<String, Integer> lemmaFrequencyMap = new HashMap<>();
            for (LemmaEntity lemma : allLemmas) {
                lemmaFrequencyMap.merge(lemma.getLemma(), lemma.getFrequency(), Integer::sum);
            }

            log.info("Количество уникальных лемм: {}", lemmaFrequencyMap.size());

            // Создание отображения лемм к спискам связанных страниц
            Map<String, List<PageEntity>> lemmaPagesMap = new HashMap<>();
            for (IndexEntity index : allIndices) {
                LemmaEntity lemma = index.getLemma();
                PageEntity page = index.getPage();
                lemmaPagesMap.computeIfAbsent(lemma.getLemma(), k -> new ArrayList<>()).add(page);
            }

            // Удаление всех записей из таблицы индексов
            indexRepository.deleteAll();

            // Получение site_id для всех лемм
            Optional<LemmaEntity> firstLemma = allLemmas.stream().findFirst();
            SiteEntity siteForLemmas = firstLemma.map(LemmaEntity::getSite).orElse(null);

            // Удаление всех записей из таблицы лемм
            lemmaRepository.deleteAll();

            // Обновление частоты лемм и сохранение индексов
            List<LemmaEntity> mergedLemmas = new ArrayList<>();
            List<IndexEntity> mergedIndices = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : lemmaFrequencyMap.entrySet()) {
                LemmaEntity lemmaEntity = new LemmaEntity();
                lemmaEntity.setLemma(entry.getKey());
                lemmaEntity.setFrequency(entry.getValue());
                lemmaEntity.setSite(siteForLemmas); // Установка site_id для каждой леммы
                mergedLemmas.add(lemmaEntity);

                List<PageEntity> relatedPages = lemmaPagesMap.getOrDefault(entry.getKey(), new ArrayList<>());
                for (PageEntity page : relatedPages) {
                    IndexEntity indexEntity = new IndexEntity();
                    indexEntity.setLemma(lemmaEntity);
                    indexEntity.setPage(page);
                    indexEntity.setRank(entry.getValue().floatValue()); // Обновление частоты в индексе
                    mergedIndices.add(indexEntity);
                }
            }

            // Сохранение обновленных лемм и индексов
            lemmaRepository.saveAll(mergedLemmas);
            indexRepository.saveAll(mergedIndices);

            log.info("Завершено объединение лемм и обновление индексов.");
        } catch (Exception e) {
            log.error("Ошибка при объединении лемм и обновлении индексов: {}", e.getMessage());
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

            pageRepository.save(pageEntity);
            log.info("Страница {} успешно индексирована", url);

        } catch (IOException e) {
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage());
        }
    }
}
