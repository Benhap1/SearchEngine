package searchengine.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import searchengine.model.LemmaEntity;
import java.util.concurrent.TimeUnit;

@Getter
@Slf4j
@Component
public class CacheManagement {

    protected final Cache<String, Boolean> pageUrlCache = Caffeine.newBuilder()
            .maximumSize(600)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    protected final Cache<String, LemmaEntity> lemmaCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    public void clearCache() {
        pageUrlCache.invalidateAll();
        lemmaCache.invalidateAll();
        log.info("Кэши очищены.");
    }
}

