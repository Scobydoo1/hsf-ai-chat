package com.hsf.aichat.repository;

import com.hsf.aichat.entity.ProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, Long> {

    Optional<ProviderConfig> findFirstByActiveTrue();

    Optional<ProviderConfig> findFirstByUseForEmbeddingsTrue();

    boolean existsByName(String name);

    List<ProviderConfig> findAllByOrderByIdAsc();
}
