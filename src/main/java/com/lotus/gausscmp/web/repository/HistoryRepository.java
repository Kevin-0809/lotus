package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.CompareHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HistoryRepository extends JpaRepository<CompareHistory, Long> {
    Page<CompareHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
