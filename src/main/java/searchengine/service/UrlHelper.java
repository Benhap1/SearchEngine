package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import searchengine.util.GlobalErrorsHandler;

import java.net.MalformedURLException;
import java.net.URL;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlHelper {

    private final UrlNormalizer urlNormalizer;
    private final GlobalErrorsHandler globalErrorsHandler;

    public boolean isInternalLink(String url, String baseUrl) {
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
}
