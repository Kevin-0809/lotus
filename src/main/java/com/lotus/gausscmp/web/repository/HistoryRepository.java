package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.CompareHistory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryRepository extends JpaRepository<CompareHistory, Long> {

    /**
     * openGauss 部分版本不支持带绑定参数的 FETCH FIRST 分页语法，使用
     * PostgreSQL/openGauss 兼容的 LIMIT/OFFSET 显式分页。
     */
    @Query(value = "SELECT * FROM compare_history ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
           nativeQuery = true)
    List<CompareHistory> findPage(@Param("limit") int limit, @Param("offset") long offset);

    @Query(value = "SELECT COUNT(*) FROM compare_history", nativeQuery = true)
    long countAll();
}
