package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.MetaSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaSequenceRepository extends JpaRepository<MetaSequence, Long> {
    List<MetaSequence> findByConnectionIdOrderBySequenceNameAsc(Long connectionId);

    @Modifying
    @Query("DELETE FROM MetaSequence s WHERE s.connectionId = :connectionId")
    void deleteByConnectionId(Long connectionId);
}
