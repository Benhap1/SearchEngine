package searchengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.PageRepository;
import searchengine.util.GlobalErrorsHandler;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageEntityCreator {

    private final PageRepository pageRepository;
    private final GlobalErrorsHandler globalErrorsHandler;

    @Transactional
    public PageEntity createPageEntity(Document document, String url, SiteEntity siteEntity) {
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
}

