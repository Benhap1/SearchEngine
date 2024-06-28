package searchengine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class GlobalErrorsHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalErrorsHandler.class);
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

    public synchronized void addError(String errorMessage) {
        errors.add(errorMessage);
        log.error(errorMessage);
    }

    public synchronized List<String> getAllErrorsAndClear() {
        List<String> currentErrors = new ArrayList<>(errors);
        errors.clear();
        return currentErrors;
    }

    public synchronized List<String> getErrors() {
        return new ArrayList<>(errors);
    }
}

