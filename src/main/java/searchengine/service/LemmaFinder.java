package searchengine.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class LemmaFinder {

    private LuceneMorphology russianMorphology;
    private LuceneMorphology englishMorphology;
    private static final String WORD_TYPE_REGEX = "\\W\\w&&[^а-яА-Я\\s]";
    private static final String[] russianParticlesNames = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ"};
    private static final String[] englishParticlesNames = new String[]{"IN", "CC", "DT"};

    @PostConstruct
    public void init() {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorphology = new EnglishLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при инициализации LuceneMorphology", e);
        }
    }

    public Map<String, Integer> collectLemmas(String text) {
        Language language = detectLanguage(text);

        String[] words = preprocessText(text, language);
        HashMap<String, Integer> lemmas = new HashMap<>();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }

            List<String> wordBaseForms = getMorphInfo(word, language);
            if (anyWordBaseBelongToParticle(wordBaseForms, language)) {
                continue;
            }

            List<String> normalForms = getNormalForms(word, language);
            if (normalForms.isEmpty()) {
                continue;
            }

            String normalWord = normalForms.get(0);
            lemmas.put(normalWord, lemmas.getOrDefault(normalWord, 0) + 1);
        }

        return lemmas;
    }

    public Set<String> getLemmaSet(String text) {
        Language language = detectLanguage(text);

        String[] textArray = preprocessText(text, language);
        Set<String> lemmaSet = new HashSet<>();
        for (String word : textArray) {
            if (!word.isEmpty() && isCorrectWordForm(word, language)) {
                List<String> wordBaseForms = getMorphInfo(word, language);
                if (anyWordBaseBelongToParticle(wordBaseForms, language)) {
                    continue;
                }
                lemmaSet.addAll(getNormalForms(word, language));
            }
        }
        return lemmaSet;
    }

    private Language detectLanguage(String text) {
        Document doc = Jsoup.parse(text);
        String lang = Objects.requireNonNull(doc.selectFirst("html")).attr("lang");

        if (!lang.isEmpty()) {
            if (lang.startsWith("ru")) {
                return Language.RUSSIAN;
            } else if (lang.startsWith("en")) {
                return Language.ENGLISH;
            }
        }


        return isRussianText(text) ? Language.RUSSIAN : Language.ENGLISH;
    }

    private boolean isRussianText(String text) {
        int russianCount = 0;
        int englishCount = 0;

        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
                russianCount++;
            } else if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.BASIC_LATIN) {
                englishCount++;
            }
        }

        return russianCount > englishCount;
    }

    private String[] preprocessText(String text, Language language) {
        if (language == Language.RUSSIAN) {
            return text.toLowerCase(Locale.ROOT)
                    .replaceAll("[^а-яА-Я\\s]", "")
                    .split("\\s+");
        } else if (language == Language.ENGLISH) {
            return text.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-zA-Z\\s]", "")
                    .split("\\s+");
        }
        return new String[0];
    }

    private List<String> getMorphInfo(String word, Language language) {
        if (language == Language.RUSSIAN) {
            return russianMorphology.getMorphInfo(word);
        } else if (language == Language.ENGLISH) {
            return englishMorphology.getMorphInfo(word);
        }
        return Collections.emptyList();
    }

    private List<String> getNormalForms(String word, Language language) {
        if (language == Language.RUSSIAN) {
            return russianMorphology.getNormalForms(word);
        } else if (language == Language.ENGLISH) {
            return englishMorphology.getNormalForms(word);
        }
        return Collections.emptyList();
    }

    private boolean anyWordBaseBelongToParticle(List<String> wordBaseForms, Language language) {
        return wordBaseForms.stream().anyMatch(wordBase -> hasParticleProperty(wordBase, language));
    }

    private boolean hasParticleProperty(String wordBase, Language language) {
        String[] particlesNames = (language == Language.RUSSIAN) ? russianParticlesNames : englishParticlesNames;
        for (String property : particlesNames) {
            if (wordBase.toUpperCase().contains(property)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCorrectWordForm(String word, Language language) {
        List<String> wordInfo = getMorphInfo(word, language);
        for (String morphInfo : wordInfo) {
            if (morphInfo.matches(WORD_TYPE_REGEX)) {
                return false;
            }
        }
        return true;
    }

    private enum Language {
        RUSSIAN,
        ENGLISH
    }
}

