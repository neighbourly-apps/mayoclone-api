package com.mayoclone.repository;

import com.mayoclone.domain.TrainNameCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainNameCatalogRepository extends JpaRepository<TrainNameCatalog, String> {
}
