package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.CompareScheduleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleRunRepository extends JpaRepository<CompareScheduleRun, Long> {
    @Query(value = "SELECT * FROM compare_schedule_run WHERE schedule_id = :sid " +
        "ORDER BY started_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<CompareScheduleRun> findRuns(@Param("sid") Long scheduleId,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    @Query(value = "SELECT count(*) FROM compare_schedule_run WHERE schedule_id = :sid", nativeQuery = true)
    long countRuns(@Param("sid") Long scheduleId);
}
