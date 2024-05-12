package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

import java.util.List;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Long> {

    void deleteBySite(SiteEntity siteEntity);
    long countBySite(SiteEntity siteEntity);
    List<PageEntity> findBySite(SiteEntity site);
}

