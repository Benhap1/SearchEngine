package searchengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.GlobalErrorsHandler;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class IndexPageCommand {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final GlobalErrorsHandler globalErrorsHandler;
    private final SiteIndexingService siteIndexingService;
    private final UrlNormalizer urlNormalizer;


    @Transactional
    public Map<String, Object> processIndexPage(String url) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (url == null || url.trim().isEmpty()) {
                response.put("result", false);
                response.put("error", "Пустой поисковый запрос");
                return response;
            }
            boolean result = indexPage(url);
            if (!result) {
                response.put("result", false);
                response.put("error", "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            } else {
                response.put("result", true);
            }
        } catch (MalformedURLException e) {
            response.put("result", false);
            response.put("error", "Неправильный формат URL");
        } catch (RuntimeException e) {
            response.put("result", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    @Transactional
    protected boolean indexPage(String url) throws MalformedURLException {
        String normalizedUrl = urlNormalizer.normalizeUrl(url);
        URL parsedUrl = new URL(normalizedUrl);
        String host = parsedUrl.getHost();
        Optional<SiteEntity> optionalSiteEntity = siteRepository.findByUrlContaining(host);
        if (optionalSiteEntity.isEmpty()) {
            return false;
        }

        SiteEntity siteEntity = optionalSiteEntity.get();
        indexPageEntity(siteEntity, normalizedUrl);
        return true;
    }


    @Transactional
    protected void indexPageEntity(SiteEntity siteEntity, String url) {
        try {
            Document document = Jsoup.connect(url).get();
            String path = new URL(url).getPath();
            Optional<PageEntity> existingPage = pageRepository.findBySiteAndPath(siteEntity, path);
            PageEntity pageEntity = existingPage.orElse(new PageEntity());

            if (existingPage.isPresent()) {
                deleteOldIndicesAndAdjustLemmas(pageEntity);
            }
            pageEntity.setSite(siteEntity);
            pageEntity.setPath(path);
            pageEntity.setContent(document.outerHtml());

            int statusCode = Jsoup.connect(url).execute().statusCode();
            pageEntity.setCode(statusCode);
            pageRepository.save(pageEntity);
            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(pageEntity.getContent());
            siteIndexingService.saveLemmasAndIndices(siteEntity, pageEntity, lemmas);
            log.info("Страница {} успешно индексирована", url);
        } catch (IOException e) {
            String errorMessage = String.format("Ошибка при индексации страницы %s: %s", url, e.getMessage());
            globalErrorsHandler.addError(errorMessage);
            log.error(errorMessage);
        }
    }


    @Transactional
    protected void deleteOldIndicesAndAdjustLemmas(PageEntity pageEntity) {
        List<IndexEntity> oldIndices = indexRepository.findByPage(pageEntity);
        indexRepository.deleteAll(oldIndices);

        for (IndexEntity index : oldIndices) {
            LemmaEntity lemma = index.getLemma();
            lemma.setFrequency(lemma.getFrequency() - index.getRank().intValue());
            lemmaRepository.save(lemma);
        }
    }
}


