package com.mayoclone.repository;

import com.mayoclone.domain.Aggregator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AggregatorRepository extends JpaRepository<Aggregator, Long> {

    List<Aggregator> findByActiveTrue();

    Optional<Aggregator> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
