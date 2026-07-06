package com.mayoclone.repository;

import com.mayoclone.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<Settlement> findByIdAndAccountId(Long id, Long accountId);
}
