package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.MetaPartition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaPartitionRepository extends JpaRepository<MetaPartition, Long> {
    List<MetaPartition> findByTableIdOrderByOrdinalAsc(Long tableId);

    @Modifying
    @Query("DELETE FROM MetaPartition p WHERE p.tableId IN (SELECT m.id FROM MetaTable m WHERE m.connectionId = :connectionId)")
    void deleteByConnectionId(Long connectionId);
}
