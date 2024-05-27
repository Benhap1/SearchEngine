package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.config.Site;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<SiteEntity, Long> {

    @Modifying
    @Query("DELETE FROM SiteEntity s WHERE s.url = :url AND s.status = :status")
    void deleteByUrlAndStatus(@Param("url") String url, @Param("status") SiteStatus status);

    Optional<SiteEntity> findByUrl(String url);

    List<SiteEntity> findByStatus(String status);

    Optional<SiteEntity> findByUrlContaining(String host);

}

