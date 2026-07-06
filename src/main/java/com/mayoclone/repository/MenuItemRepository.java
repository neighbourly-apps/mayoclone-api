package com.mayoclone.repository;

import com.mayoclone.domain.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<MenuItem> findByAccountIdAndAvailableOrderByCreatedAtDesc(Long accountId, boolean available);

    Optional<MenuItem> findByIdAndAccountId(Long id, Long accountId);
}
