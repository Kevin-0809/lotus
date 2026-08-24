package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.CompareTableConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CompareTableConfigRepository extends JpaRepository<CompareTableConfig, Long> {
    List<CompareTableConfig> findAllByOrderByTableTypeAscTableNameAsc();
    List<CompareTableConfig> findByEnabledTrueAndTableType(CompareTableConfig.TableType type);
    List<CompareTableConfig> findByTableNameAndTableType(String tableName, CompareTableConfig.TableType type);
    List<CompareTableConfig> findByTableNameAndTableTypeAndIdNot(String tableName, CompareTableConfig.TableType type, Long id);
}
