package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
public interface LemmaRepository extends JpaRepository<LemmaEntity, Long> {

    Optional<LemmaEntity> findByLemmaAndSite(String lemma, SiteEntity siteEntity);

    List<LemmaEntity> findByLemmaInAndSite(Set<String> uniqueLemmas, SiteEntity siteEntity);

}