package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

import java.util.Collection;
import java.util.List;

@Repository
public interface IndexRepository extends JpaRepository<IndexEntity, Long> {

}