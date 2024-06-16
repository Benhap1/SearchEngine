package searchengine.dto.search;


import lombok.Data;

@Data
public class ErrorResponse {
    private boolean result;
    private String error;
}
