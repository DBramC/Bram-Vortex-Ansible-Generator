package com.christos_bramis.bram_vortex_ansible_generator.repository;

import com.christos_bramis.bram_vortex_ansible_generator.entity.AnsibleJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnsibleJobRepository extends JpaRepository<AnsibleJob, String> {
    // Μπορεί να χρειαστείς να βρεις το Terraform Job με βάση το Analysis Job
    AnsibleJob findByAnalysisJobId(String analysisJobId);
}