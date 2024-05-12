package searchengine.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import searchengine.model.SiteStatus;

@Setter
@Getter

public class Site {
    private String url;
    private String name;
}
