package searchengine.dto.search;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@Data
@JsonPropertyOrder({ "result", "count", "data" })
public class SearchResults {
    private boolean result;
    private int count;
    private List<SearchResultDto> data;

    public SearchResults(boolean result, int count, List<SearchResultDto> data) {
        this.result = result;
        this.count = count;
        this.data = data;
    }
}
