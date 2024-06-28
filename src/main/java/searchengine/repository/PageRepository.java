package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Long> {

    long count();
    int countBySite(SiteEntity site);
    Optional<PageEntity> findBySiteAndPath(SiteEntity siteEntity, String path);

    @Query("SELECT p FROM PageEntity p JOIN IndexEntity i ON p.id = i.page.id JOIN LemmaEntity l ON i.lemma.id = l.id WHERE l.lemma IN :lemmas AND p.site.url = :site GROUP BY p.id HAVING COUNT(DISTINCT l.lemma) = :lemmasSize")
    List<PageEntity> findPagesByLemmasAndSite(@Param("lemmas") List<String> lemmas, @Param("site") String site, @Param("lemmasSize") long lemmasSize);
}
