package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.CompareSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<CompareSchedule, Long> {
    List<CompareSchedule> findByEnabledTrue();
}
