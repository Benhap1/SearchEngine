package searchengine;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import searchengine.config.Site;
import searchengine.controllers.ApiController;
import searchengine.dto.search.SearchResultDto;
import searchengine.dto.search.SearchResults;
import searchengine.dto.statistics.*;
import searchengine.services.StatisticsService;
import searchengine.service.SiteIndexingService;
import java.net.MalformedURLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import searchengine.config.SitesList;


@Transactional
public class ApiControllerTest {

    @InjectMocks
    private ApiController apiController;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private SiteIndexingService siteIndexingService;

    @Mock
    private SitesList sitesList;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void testStatistics() {
        StatisticsResponse statisticsResponse = new StatisticsResponse();
        StatisticsData statisticsData = new StatisticsData();
        TotalStatistics totalStatistics = new TotalStatistics();
        totalStatistics.setSites(1);
        totalStatistics.setPages(100);
        totalStatistics.setLemmas(1000);
        totalStatistics.setIndexing(false);
        DetailedStatisticsItem detailedStatisticsItem = new DetailedStatisticsItem();
        detailedStatisticsItem.setUrl("http://example.com");
        detailedStatisticsItem.setName("Example Site");
        detailedStatisticsItem.setStatus("INDEXED");
        detailedStatisticsItem.setStatusTime(1625140800L); // Примерное значение времени
        detailedStatisticsItem.setError(null);
        detailedStatisticsItem.setPages(100);
        detailedStatisticsItem.setLemmas(1000);
        statisticsData.setTotal(totalStatistics);
        statisticsData.setDetailed(Collections.singletonList(detailedStatisticsItem));
        statisticsResponse.setResult(true);
        statisticsResponse.setStatistics(statisticsData);
        when(statisticsService.getStatistics()).thenReturn(statisticsResponse);
        ResponseEntity<StatisticsResponse> responseEntity = apiController.statistics();
        assertEquals(ResponseEntity.ok(statisticsResponse).getStatusCode(), responseEntity.getStatusCode());
        assertEquals(statisticsResponse, responseEntity.getBody());
    }

    @Test
    public void testIndexPage_Success() throws MalformedURLException {
        when(siteIndexingService.indexPage(anyString())).thenReturn(true);
        ResponseEntity<Map<String, Object>> responseEntity = apiController.indexPage("http://example.com");
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(true, Objects.requireNonNull(responseEntity.getBody()).get("result"));
        assertNull(responseEntity.getBody().get("error"));
        verify(siteIndexingService, times(1)).indexPage("http://example.com");
    }

    @Test
    public void testIndexPage_Failure() throws MalformedURLException {
        when(siteIndexingService.indexPage(anyString())).thenReturn(false);
        ResponseEntity<Map<String, Object>> responseEntity = apiController.indexPage("http://example.com");
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(false, Objects.requireNonNull(responseEntity.getBody()).get("result"));
        assertEquals("Данная страница находится за пределами сайтов, указанных в конфигурационном файле",
                responseEntity.getBody().get("error"));
        verify(siteIndexingService, times(1)).indexPage("http://example.com");
    }

    @Test
    public void testIndexPage_Exception() throws MalformedURLException {
        when(siteIndexingService.indexPage(anyString())).thenThrow(new RuntimeException("Mock exception"));
        ResponseEntity<Map<String, Object>> responseEntity = apiController.indexPage("http://example.com");
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals(false, Objects.requireNonNull(responseEntity.getBody()).get("result"));
        assertEquals("Mock exception", responseEntity.getBody().get("error"));
        verify(siteIndexingService, times(1)).indexPage("http://example.com");
    }


    @Test
    void testStartIndexing_Success() {
        List<Site> mockSites = List.of(new Site("http://example.com", "Example Site"));
        when(sitesList.getSites()).thenReturn(mockSites);
        when(siteIndexingService.startIndexing(mockSites)).thenReturn(true);
        ResponseEntity<String> responseEntity = apiController.startIndexing();
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals("{\"result\": true}", responseEntity.getBody());
        verify(siteIndexingService, times(1)).startIndexing(mockSites);
    }

    @Test
    void testStartIndexing_Failure() {
        List<Site> mockSites = List.of(new Site("http://example.com", "Example Site"));
        when(sitesList.getSites()).thenReturn(mockSites);
        when(siteIndexingService.startIndexing(mockSites)).thenReturn(false);
        ResponseEntity<String> responseEntity = apiController.startIndexing();
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertTrue(Objects.requireNonNull(responseEntity.getBody()).contains("Индексация уже запущена"));
        verify(siteIndexingService, times(1)).startIndexing(mockSites);
    }

    @Test
    void testStopIndexing_Success() {
        when(siteIndexingService.stopIndex()).thenReturn(true);
        ResponseEntity<?> responseEntity = apiController.stopIndexing();
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals("{\"result\": true}", responseEntity.getBody());
        verify(siteIndexingService, times(1)).stopIndex();
    }

    @Test
    void testStopIndexing_Failure() {
        when(siteIndexingService.stopIndex()).thenReturn(false);
        ResponseEntity<?> responseEntity = apiController.stopIndexing();
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertTrue(Objects.requireNonNull(responseEntity.getBody()).toString().contains("Индексация не запущена"));
        verify(siteIndexingService, times(1)).stopIndex();
    }

    @Test
    void testSearchWithoutSiteParameter() {
        String query = "test query";
        int offset = 0;
        int limit = 10;
        List<Site> mockSites = Arrays.asList(
                new Site("http://site1.com", "Example Site"),
                new Site("http://site2.com", "Example Site")
        );
        when(sitesList.getSites()).thenReturn(mockSites);
        SearchResults mockSearchResults = createMockSearchResults();
        when(siteIndexingService.search(query, null, offset, limit)).thenReturn(mockSearchResults);
        ResponseEntity<?> responseEntity = apiController.search(query, null, offset, limit);
        assertEquals(HttpStatus.OK.value(), responseEntity.getStatusCode().value());
        Object responseBody = responseEntity.getBody();
        assertInstanceOf(SearchResults.class, responseBody);
        SearchResults searchResults = (SearchResults) responseBody;
        assertEquals(mockSearchResults.isResult(), searchResults.isResult());
        assertEquals(mockSearchResults.getCount(), searchResults.getCount());
        assertEquals(mockSearchResults.getData().size(), searchResults.getData().size());
    }

    private SearchResults createMockSearchResults() {
        List<SearchResultDto> mockResults = Arrays.asList(
                new SearchResultDto("http://site1.com", "Site 1", "/page1", "Title 1", "Snippet 1", 0.8),
                new SearchResultDto("http://site2.com", "Site 2", "/page2", "Title 2", "Snippet 2", 0.7)
        );
        return new SearchResults(true, mockResults.size(), mockResults);
    }
}
