package searchengine.dto.search;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonPropertyOrder({ "result", "count", "data" })
public class SearchResults {
    private boolean result;
    private int count;
    private List<SearchResultDto> data;
}
