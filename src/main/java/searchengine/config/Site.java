package searchengine.config;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor // для тестов
public class Site {
    private String url;
    private String name;
}
