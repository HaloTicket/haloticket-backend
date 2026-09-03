package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SearchLogRepository extends JpaRepository<SearchLog, String> {

    Optional<SearchLog> findByQueryTermIgnoreCase(String queryTerm);

    @Query("SELECT s.queryTerm FROM SearchLog s ORDER BY s.searchCount DESC")
    List<String> findTopSearchedKeywords(Pageable pageable);
}
