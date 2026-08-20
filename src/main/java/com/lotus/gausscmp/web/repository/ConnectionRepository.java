package com.lotus.gausscmp.web.repository;

import com.lotus.gausscmp.web.entity.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectionRepository extends JpaRepository<DbConnection, Long> {
    List<DbConnection> findAllByOrderByIdAsc();
    Optional<DbConnection> findByName(String name);
    boolean existsByName(String name);
}
