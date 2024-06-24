package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexEntity;
import searchengine.model.PageEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

@Repository
public interface IndexRepository extends JpaRepository<IndexEntity, Long> {

    @Query("SELECT ie FROM IndexEntity ie WHERE ie.page = :page AND ie.lemma.lemma IN :lemmas")
    List<IndexEntity> findByPageAndLemmas(@Param("page") PageEntity page, @Param("lemmas") List<String> lemmas);

    List<IndexEntity> findByPage(PageEntity pageEntity);

    void deleteByPage(PageEntity pageEntity);
}