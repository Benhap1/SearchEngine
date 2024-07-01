package searchengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.SiteRepository;
import searchengine.util.GlobalErrorsHandler;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteStatusUpdateService {

    private final SiteRepository siteRepository;
    private final GlobalErrorsHandler globalErrorsHandler;

    @Transactional
    public void updateSiteStatus(SiteEntity siteEntity, boolean stopRequested, boolean hasError, String errorMessage) {
        if (hasError) {
            handleFailed(siteEntity, errorMessage);
        } else if (stopRequested) {
            handleStopRequested(siteEntity);
        } else {
            handleIndexComplete(siteEntity);
        }
    }

    private void handleFailed(SiteEntity siteEntity, String errorMessage) {
        log.info("Индексация завершена с ошибкой.");
        siteEntity.setStatus(SiteStatus.FAILED.name());
        siteEntity.setStatusTime(LocalDateTime.now());

        if (errorMessage != null) {
            String truncatedErrorMessage = errorMessage.length() > 256 ? errorMessage.substring(0, 256) : errorMessage;
            siteEntity.setLastError(truncatedErrorMessage);
        } else {
            siteEntity.setLastError("Неизвестная ошибка при индексации.");
        }

        saveSiteEntity(siteEntity);
    }

    private void handleStopRequested(SiteEntity siteEntity) {
        log.info("Индексация остановлена пользователем.");
        siteEntity.setStatus(SiteStatus.FAILED.name());
        siteEntity.setStatusTime(LocalDateTime.now());
        siteEntity.setLastError("Индексация прервана пользователем!");
        saveSiteEntity(siteEntity);
    }

    private void handleIndexComplete(SiteEntity siteEntity) {
        log.info("Начало обновления статуса сайта: {}", siteEntity.getUrl());
        siteEntity.setStatus(SiteStatus.INDEXED.name());
        siteEntity.setStatusTime(LocalDateTime.now());
        siteEntity.setLastError(null);
        saveSiteEntity(siteEntity);
    }

    private void saveSiteEntity(SiteEntity siteEntity) {
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
