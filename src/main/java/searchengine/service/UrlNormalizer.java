package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;

@Slf4j
@Component
public class UrlNormalizer {

    public String normalizeUrl(String url) {
        try {
            URL uri = new URL(url);
            String path = uri.getPath().replaceAll("/{2,}", "/").replaceAll("/$", "");
            if (path.isEmpty()) {
                path = "/";
            }
            return new URL(uri.getProtocol(), uri.getHost(), path).toString().toLowerCase();
        } catch (MalformedURLException e) {
            log.error("Ошибка при нормализации URL: {}", e.getMessage());
            return url.toLowerCase().replaceAll("/{2,}", "/").replaceAll("/$", "");
        }
    }
}
