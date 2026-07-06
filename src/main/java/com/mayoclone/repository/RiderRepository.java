package com.mayoclone.repository;

import com.mayoclone.domain.Rider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiderRepository extends JpaRepository<Rider, Long> {

    List<Rider> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<Rider> findByIdAndAccountId(Long id, Long accountId);
}
