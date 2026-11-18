package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Truy vấn nhật ký từ khóa tìm kiếm {@code SearchLog}, mỗi từ khóa một dòng kèm số lần tìm. */
@Repository
public interface SearchLogRepository extends JpaRepository<SearchLog, String> {

    /** Tìm dòng nhật ký của một từ khóa, không phân biệt hoa thường. */
    Optional<SearchLog> findByQueryTermIgnoreCase(String queryTerm);

    /** Lấy các từ khóa được tìm nhiều nhất, giảm dần theo số lần tìm; số lượng do {@code pageable} quyết định. */
    @Query("SELECT s.queryTerm FROM SearchLog s ORDER BY s.searchCount DESC")
    List<String> findTopSearchedKeywords(Pageable pageable);
}
