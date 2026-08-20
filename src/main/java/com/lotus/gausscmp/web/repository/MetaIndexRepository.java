package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.MetaIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaIndexRepository extends JpaRepository<MetaIndex, Long> {
    List<MetaIndex> findByTableId(Long tableId);

    @Modifying
    @Query("DELETE FROM MetaIndex i WHERE i.tableId IN (SELECT m.id FROM MetaTable m WHERE m.connectionId = :connectionId)")
    void deleteByConnectionId(Long connectionId);
}
