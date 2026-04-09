package com.christos_bramis.bram_vortex_ansible_generator.controller;

import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.service.AnsibleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/ansible")
public class Ansible {

    private final AnsibleService ansibleService;
    private final AnsibleJobRepository ansibleJobRepository;

    public Ansible(AnsibleService ansibleService, AnsibleJobRepository ansibleJobRepository) {
        this.ansibleService = ansibleService;
        this.ansibleJobRepository = ansibleJobRepository;
    }

    /**
     * Endpoint που δέχεται το Webhook από τον Repo Analyzer.
     * Πλέον το userId έρχεται από το επικυρωμένο JWT Token.
     */
    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generateAnsible(
            @PathVariable String analysisJobId,
            Authentication auth) { // <--- Λήψη του χρήστη από το Security Context

        String userId = auth.getName();
        String token = ((Jwt) Objects.requireNonNull(auth.getPrincipal())).getTokenValue();
        System.out.println("🚀 [ANSIBLE CONTROLLER] Webhook received for Job: " + analysisJobId + " from User: " + userId);

        try {
            String ansibleJobId = UUID.randomUUID().toString();

            // Ξεκινάμε την παραγωγή (Async) χρησιμοποιώντας το userId από το Token
            ansibleService.generateAndSaveAnsible(ansibleJobId, analysisJobId, userId, token);

            return ResponseEntity.ok(ansibleJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    @GetMapping("/download/by-analysis/{analysisJobId}")
    public ResponseEntity<byte[]> downloadAnsibleByAnalysisId(
            @PathVariable String analysisJobId,
            Authentication auth) {

        String userId = auth.getName();
        System.out.println("📦 [ANSIBLE] Download request for Analysis Job: " + analysisJobId + " by User: " + userId);

        return ansibleJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized access attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // ΕΔΩ Η ΔΙΟΡΘΩΣΗ: Προσθήκη ελέγχου length == 0
                    if (!"COMPLETED".equals(job.getStatus()) || job.getAnsibleZip() == null || job.getAnsibleZip().length == 0) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build(); // Επιστρέφει 202
                    }

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-ansible-" + analysisJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getAnsibleZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}