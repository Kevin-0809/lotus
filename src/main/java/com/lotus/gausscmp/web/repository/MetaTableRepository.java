package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.MetaTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaTableRepository extends JpaRepository<MetaTable, Long> {
    List<MetaTable> findByConnectionIdOrderByTableNameAsc(Long connectionId);
    List<MetaTable> findByConnectionIdAndTableName(Long connectionId, String tableName);

    @Modifying
    @Query("DELETE FROM MetaTable m WHERE m.connectionId = :connectionId")
    void deleteByConnectionId(Long connectionId);
}
