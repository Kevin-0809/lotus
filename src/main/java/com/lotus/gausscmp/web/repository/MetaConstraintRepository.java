package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.MetaConstraint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaConstraintRepository extends JpaRepository<MetaConstraint, Long> {
    List<MetaConstraint> findByTableId(Long tableId);

    @Modifying
    @Query("DELETE FROM MetaConstraint c WHERE c.tableId IN (SELECT m.id FROM MetaTable m WHERE m.connectionId = :connectionId)")
    void deleteByConnectionId(Long connectionId);
}
