package searchengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.search.SearchResultDto;
import searchengine.dto.search.SearchResults;
import searchengine.model.IndexEntity;
import searchengine.model.PageEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchCommand {

    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final SitesList sitesList;


    public SearchResults search(String query, String site, int offset, int limit) {
        validateSearchParameters(query);
        List<SearchResultDto> allResults = new ArrayList<>();
        if (site == null || site.isEmpty()) {
            for (Site currentSite : sitesList.getSites()) {
                List<SearchResultDto> siteResults = performSearch(query, currentSite.getUrl());
                allResults.addAll(siteResults);
            }
        } else {
            List<SearchResultDto> siteResults = performSearch(query, site);
            allResults.addAll(siteResults);
        }

        allResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());
        int startIndex = Math.min(offset, allResults.size());
        int endIndex = Math.min(offset + limit, allResults.size());
        List<SearchResultDto> paginatedResults = allResults.subList(startIndex, endIndex);
        log.info("Search completed for query: '{}', site: '{}', offset: {}, limit: {}", query, site, offset, limit);
        return new SearchResults(true, allResults.size(), paginatedResults);
    }

    private void validateSearchParameters(String query) {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Пустой поисковый запрос");
        }
    }

    private List<SearchResultDto> performSearch(String query, String site) {
        Set<String> lemmas = lemmaFinder.getLemmaSet(query);
        double maxAllowedFrequencyPercentage = 1.0;
        Set<String> filteredLemmas = filterFrequentLemmas(lemmas, maxAllowedFrequencyPercentage);
        List<String> sortedLemmas = sortLemmasByFrequency(filteredLemmas);
        List<PageEntity> pages = findPagesByLemmas(sortedLemmas, site);
        if (pages.isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchResultDto> searchResults = new ArrayList<>();
        double maxRelevance = 0.0;
        for (PageEntity page : pages) {
            double relevance = calculateRelevance(page, sortedLemmas);
            if (relevance > maxRelevance) {
                maxRelevance = relevance;
            }
            searchResults.add(createSearchResult(page, relevance, sortedLemmas));
        }
        for (SearchResultDto result : searchResults) {
            result.setRelevance(result.getRelevance() / maxRelevance);
        }
        searchResults.sort(Comparator.comparingDouble(SearchResultDto::getRelevance).reversed());
        return searchResults;
    }

    private List<String> sortLemmasByFrequency(Set<String> lemmas) {
        Map<String, Integer> lemmaFrequencyMap = new HashMap<>();
        for (String lemma : lemmas) {
            int frequency = lemmaRepository.countByLemma(lemma);
            lemmaFrequencyMap.put(lemma, frequency);
        }

        return lemmaFrequencyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private Set<String> filterFrequentLemmas(Set<String> lemmas, double maxAllowedFrequencyPercentage) {
        Set<String> filteredLemmas = new HashSet<>();
        int totalPageCount = (int) pageRepository.count();

        for (String lemma : lemmas) {
            int lemmaFrequency = lemmaRepository.countByLemma(lemma);
            double frequencyPercentage = (double) lemmaFrequency / totalPageCount;
            if (frequencyPercentage <= maxAllowedFrequencyPercentage) {
                filteredLemmas.add(lemma);
            }
        }
        return filteredLemmas;
    }

    private List<PageEntity> findPagesByLemmas(List<String> sortedLemmas, String site) {
        return pageRepository.findPagesByLemmasAndSite(sortedLemmas, site, sortedLemmas.size());
    }

    private double calculateRelevance(PageEntity page, List<String> sortedLemmas) {
        List<IndexEntity> indices = indexRepository.findByPageAndLemmas(page, sortedLemmas);
        return indices.stream()
                .mapToDouble(IndexEntity::getRank)
                .sum();
    }

    private SearchResultDto createSearchResult(PageEntity page, double relevance, List<String> sortedLemmas) {
        SearchResultDto result = new SearchResultDto();
        result.setSite(page.getSite().getUrl());
        result.setSiteName(page.getSite().getName());
        result.setUri(page.getPath());
        result.setTitle(extractTitle(page.getContent()));
        result.setSnippet(createSnippet(page.getContent(), sortedLemmas));
        result.setRelevance(relevance);
        System.out.println("Created SearchResultDto: " + result);
        return result;
    }


    private String extractTitle(String content) {
        Document document = Jsoup.parse(content);
        return document.title();
    }


    private String createSnippet(String content, List<String> sortedLemmas) {
        String cleanContent = Jsoup.parse(content).text();
        String bestSnippet = "";
        for (String lemma : sortedLemmas) {
            int keywordIndex = cleanContent.indexOf(lemma);
            if (keywordIndex != -1) {
                int snippetStart = Math.max(0, keywordIndex - 150);
                int snippetEnd = Math.min(cleanContent.length(), keywordIndex + lemma.length() + 150);
                bestSnippet = cleanContent.substring(snippetStart, snippetEnd);
                bestSnippet = highlightKeywords(bestSnippet, sortedLemmas);

                return bestSnippet;
            }
        }
        if (cleanContent.length() > 300) {
            bestSnippet = cleanContent.substring(0, 300);
        }
        bestSnippet = highlightKeywords(bestSnippet, sortedLemmas);
        return bestSnippet;
    }


    private String highlightKeywords(String snippet, List<String> sortedLemmas) {
        StringBuilder snippetBuilder = new StringBuilder();
        String[] words = snippet.split("\\s+");
        boolean previousWordWasHighlighted = false;

        for (String word : words) {
            List<String> lemmas = lemmaFinder.getLemmaSet(word).stream().toList();
            String lemma = lemmas.isEmpty() ? word : lemmas.get(0); // Use first lemma if available
            boolean highlightWord = sortedLemmas.contains(lemma);

            if (highlightWord) {
                if (!previousWordWasHighlighted) {
                    snippetBuilder.append("<b>");
                }
                snippetBuilder.append(word).append(" ");
                previousWordWasHighlighted = true;
            } else {
                if (previousWordWasHighlighted) {
                    snippetBuilder.append("</b>");
                }
                snippetBuilder.append(word).append(" ");
                previousWordWasHighlighted = false;
            }
        }
        if (previousWordWasHighlighted) {
            snippetBuilder.append("</b>");
        }
        return snippetBuilder.toString().trim();
    }
}

