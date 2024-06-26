package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Long> {

    List<PageEntity> findBySite(SiteEntity site);

    Optional<PageEntity> findBySiteAndPath(SiteEntity siteEntity, String path);
}

