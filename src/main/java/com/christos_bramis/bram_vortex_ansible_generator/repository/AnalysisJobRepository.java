package com.christos_bramis.bram_vortex_ansible_generator.repository;

import com.christos_bramis.bram_vortex_ansible_generator.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {
}