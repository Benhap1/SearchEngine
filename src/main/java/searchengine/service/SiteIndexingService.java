package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

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

    // Кэш проверки существования URL страниц
    private final ConcurrentHashMap<String, Boolean> pageUrlCache = new ConcurrentHashMap<>();

    private volatile boolean stopRequested = false;

    @Transactional
    public void indexSites(List<Site> sites) {
        log.info("Начало индексации сайтов: {}", sites);

        // Сброс флага остановки перед началом новой индексации
        stopRequested = false;

        ForkJoinPool forkJoinPool = new ForkJoinPool();
        try {
            forkJoinPool.submit(() -> sites.parallelStream().forEach(this::indexSite));

            forkJoinPool.shutdown();
            boolean terminated = forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            if (!terminated) {
                log.warn("ForkJoinPool не завершился в указанный срок");
            }

            // Очистка кэша после завершения всех задач
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

    public void stopIndexing() {
        stopRequested = true; // Установка флага для остановки индексации

        // Присваиваем статус FAILED всем сайтам, которые индексируются в данный момент
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

        // Интеграция лемматизации и сохранения лемм и индексов
        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());

//         Обновление лемм и индексов
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

        ForkJoinPool forkJoinPool = new ForkJoinPool();
        try {
            // Передаем задачи индексации каждой ссылки в ForkJoinPool
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

        // Поиск сайта в базе данных по доменному имени
        Optional<SiteEntity> optionalSiteEntity = siteRepository.findByUrlContaining(host);
        if (optionalSiteEntity.isEmpty()) {
            return false; // Сайт не найден
        }

        SiteEntity siteEntity = optionalSiteEntity.get();

        // Создание или обновление страницы
        indexPageEntity(siteEntity, url);

        return true;
    }

    private void indexPageEntity(SiteEntity siteEntity, String url) {
        try {
            Document document = Jsoup.connect(url).get();
            String path = new URL(url).getPath();

            // Проверка, существует ли уже запись о странице
            Optional<PageEntity> existingPage = pageRepository.findBySiteAndPath(siteEntity, path);
            PageEntity pageEntity = existingPage.orElse(new PageEntity());

            // Обновление данных страницы
            pageEntity.setSite(siteEntity);
            pageEntity.setPath(path);
            pageEntity.setContent(document.outerHtml());

            int statusCode = Jsoup.connect(url).execute().statusCode();
            pageEntity.setCode(statusCode);

            // Сохранение или обновление записи в базе данных
            pageRepository.save(pageEntity);
            log.info("Страница {} успешно индексирована", url);

        } catch (IOException e) {
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage());
        }
    }

    public void saveLemmasAndIndices(SiteEntity siteEntity, PageEntity pageEntity, Map<String, Integer> lemmas) {
        lemmas.forEach((lemmaText, count) -> {
            // Поиск леммы в базе данных
            Optional<LemmaEntity> optionalLemmaEntity = lemmaRepository.findByLemma(lemmaText);
            LemmaEntity lemmaEntity;
            if (optionalLemmaEntity.isPresent()) {
                // Лемма уже существует
                lemmaEntity = optionalLemmaEntity.get();
                lemmaEntity.setFrequency(lemmaEntity.getFrequency() + 1); // Увеличиваем частоту
            } else {
                // Леммы нет в базе данных, создаем новую
                lemmaEntity = new LemmaEntity();
                lemmaEntity.setLemma(lemmaText);
                lemmaEntity.setFrequency(1); // Устанавливаем частоту равной 1
            }
            lemmaEntity.setSite(siteEntity);

            // Сохранение леммы
            lemmaRepository.save(lemmaEntity);

            // Создание записи в таблице index
            IndexEntity indexEntity = new IndexEntity();
            indexEntity.setPage(pageEntity);
            indexEntity.setLemma(lemmaEntity);
            indexEntity.setRank(count.floatValue());
            indexRepository.save(indexEntity);
        });
    }
}
