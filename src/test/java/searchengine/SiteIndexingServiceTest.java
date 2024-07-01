//package searchengine;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.mockito.junit.jupiter.MockitoSettings;
//import org.mockito.quality.Strictness;
//import searchengine.config.Site;
//import searchengine.config.SitesList;
//import searchengine.model.SiteEntity;
//import searchengine.repository.IndexRepository;
//import searchengine.repository.LemmaRepository;
//import searchengine.repository.PageRepository;
//import searchengine.repository.SiteRepository;
//import searchengine.service.LemmaFinder;
//import searchengine.service.SearchCommand;
//import searchengine.service.SiteIndexingService;
//import searchengine.util.GlobalErrorsHandler;
//import java.util.Optional;
//
//
//@ExtendWith(MockitoExtension.class)
//@MockitoSettings(strictness = Strictness.LENIENT)
//public class SiteIndexingServiceTest {
//
//    @Mock
//    private SiteRepository siteRepository;
//
//    @Mock
//    private PageRepository pageRepository;
//
//    @Mock
//    private LemmaRepository lemmaRepository;
//
//    @Mock
//    private IndexRepository indexRepository;
//
//    @Mock
//    private GlobalErrorsHandler globalErrorsHandler;
//
//    @Mock
//    private SearchCommand searchCommand;
//
//
//    @InjectMocks
//    private SiteIndexingService siteIndexingService;
//
//    @BeforeEach
//    public void setUp() {
//        MockitoAnnotations.openMocks(this);
//    }
//
//
//    static class TestableSiteIndexingService extends SiteIndexingService {
//        public TestableSiteIndexingService(SiteRepository siteRepository, PageRepository pageRepository, LemmaRepository lemmaRepository,
//                                           IndexRepository indexRepository, LemmaFinder lemmaFinder, SitesList sitesList,
//                                           GlobalErrorsHandler globalErrorsHandler) {
//            super(siteRepository, pageRepository, lemmaRepository, indexRepository, lemmaFinder, sitesList, globalErrorsHandler);
//        }
//
//        @Override
//        protected void siteIndexingStatusAfterStart(Site site) {
//            super.siteIndexingStatusAfterStart(site);
//        }
//    }
//
//    @Test
//    public void testUpdateSiteIndexingStatus() {
//        Site site = new Site();
//        site.setUrl("http://example.com");
//        site.setName("Example");
//        TestableSiteIndexingService testableService = new TestableSiteIndexingService(siteRepository, pageRepository, lemmaRepository,
//                indexRepository, null, null, globalErrorsHandler);
//        when(indexRepository.count()).thenReturn(1L);
//        when(lemmaRepository.count()).thenReturn(1L);
//        when(pageRepository.count()).thenReturn(1L);
//        when(siteRepository.count()).thenReturn(1L);
//        when(siteRepository.findByUrl(any(String.class))).thenReturn(Optional.of(new SiteEntity()));
//        testableService.siteIndexingStatusAfterStart(site);
//        verify(indexRepository).deleteAllInBatch();
//        verify(lemmaRepository).deleteAllInBatch();
//        verify(pageRepository).deleteAllInBatch();
//        verify(siteRepository).deleteAllInBatch();
//        verify(siteRepository).save(any(SiteEntity.class));
//    }
//
//    @Test
//    public void testNormalizeUrl_validUrl() {
//        String inputUrl = "http://example.com/path//to//resource/";
//        String normalizedUrl = invokeMethod(siteIndexingService, "normalizeUrl", inputUrl);
//        assertEquals("http://example.com/path/to/resource", normalizedUrl);
//    }
//
//
//    @Test
//    public void testIsInternalLink_internalLink() {
//        String baseUrl = "http://example.com";
//        String internalUrl = "http://example.com/path/to/resource/";
//        boolean result = Boolean.TRUE.equals(invokeMethod(siteIndexingService, "isInternalLink", internalUrl, baseUrl));
//        assertTrue(result);
//    }
//
//    @Test
//    public void testIsInternalLink_externalLink() {
//        String baseUrl = "http://example.com";
//        String externalUrl = "http://external.com/path/to/resource/";
//        boolean result = Boolean.TRUE.equals(invokeMethod(siteIndexingService, "isInternalLink", externalUrl, baseUrl));
//        assertFalse(result);
//    }
//
//}
