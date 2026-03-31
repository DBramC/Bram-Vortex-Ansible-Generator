package com.christos_bramis.bram_vortex_ansible_generator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ansible_jobs") // Ο δικός του, ανεξάρτητος πίνακας
public class AnsibleJob {

    @Id
    private String id; // Το ID αυτού του Ansible Job

    @Column(name = "analysis_job_id", nullable = false)
    private String analysisJobId; // Κρατάμε το ID της ανάλυσης για reference

    private String userId;

    private String status; // π.χ. GENERATING, COMPLETED, FAILED

    // ΕΔΩ ΕΙΝΑΙ Η ΜΑΓΕΙΑ: Η Postgres θα το κάνει BYTEA (BLOB)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "ansible_zip", columnDefinition = "bytea")
    private byte[] ansibleZip;

    // --- Getters & Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAnalysisJobId() { return analysisJobId; }
    public void setAnalysisJobId(String analysisJobId) { this.analysisJobId = analysisJobId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public byte[] getAnsibleZip() { return ansibleZip; }
    public void setAnsibleZip(byte[] ansibleZip) { this.ansibleZip = ansibleZip; }
}