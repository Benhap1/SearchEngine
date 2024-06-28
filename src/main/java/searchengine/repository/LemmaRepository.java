package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;
import java.util.Optional;


@Repository
public interface LemmaRepository extends JpaRepository<LemmaEntity, Long> {

    long count();
    int countBySite(SiteEntity site);
    Optional<LemmaEntity> findByLemmaAndSite(String lemma, SiteEntity siteEntity);
    int countByLemma(String lemma);

}