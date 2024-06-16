package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteEntity;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;


import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData statisticsData = new StatisticsData();

        List<SiteEntity> sites = siteRepository.findAll();
        int totalSites = sites.size();
        int totalPages = (int) pageRepository.count();
        int totalLemmas = (int) lemmaRepository.count();
        boolean indexingInProgress = sites.stream().anyMatch(site -> site.getStatus().equals("INDEXING"));

        TotalStatistics total = new TotalStatistics();
        total.setSites(totalSites);
        total.setPages(totalPages);
        total.setLemmas(totalLemmas);
        total.setIndexing(indexingInProgress);
        statisticsData.setTotal(total);

        List<DetailedStatisticsItem> detailedList = sites.stream().map(site -> {
            DetailedStatisticsItem detailed = new DetailedStatisticsItem();
            detailed.setUrl(site.getUrl());
            detailed.setName(site.getName());
            detailed.setStatus(site.getStatus());
            detailed.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.UTC));
            detailed.setError(site.getLastError());
            detailed.setPages(pageRepository.countBySite(site));
            detailed.setLemmas(lemmaRepository.countBySite(site));
            return detailed;
        }).collect(Collectors.toList());

        statisticsData.setDetailed(detailedList);

        response.setResult(true);
        response.setStatistics(statisticsData);

        return response;
    }
}