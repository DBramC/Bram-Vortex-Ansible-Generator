package com.christos_bramis.bram_vortex_ansible_generator.controller;

import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.service.AnsibleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
        System.out.println("🚀 [ANSIBLE CONTROLLER] Webhook received for Job: " + analysisJobId + " from User: " + userId);

        try {
            String ansibleJobId = UUID.randomUUID().toString();

            // Ξεκινάμε την παραγωγή (Async) χρησιμοποιώντας το userId από το Token
            ansibleService.generateAndSaveAnsible(ansibleJobId, analysisJobId, userId);

            return ResponseEntity.ok(ansibleJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    /**
     * Endpoint για το κατέβασμα του ZIP.
     * Το Security Check γίνεται πλέον αυτόματα με το Token του χρήστη.
     */
    @GetMapping("/download/by-analysis/{analysisJobId}") // <--- Αλλάζουμε το path για σαφήνεια
    public ResponseEntity<byte[]> downloadAnsibleByAnalysisId(
            @PathVariable String analysisJobId, // <--- Πλέον δεχόμαστε το Analysis ID
            Authentication auth) {

        String userId = auth.getName();
        System.out.println("📦 [ANSIBLE] Download request for Analysis Job: " + analysisJobId + " by User: " + userId);

        // Χρησιμοποιούμε τη νέα μέθοδο findByAnalysisJobId αντί για την findById
        return ansibleJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    // SECURITY CHECK: Έλεγχος αν το job ανήκει στον χρήστη
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized access attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // Έλεγχος αν η παραγωγή έχει ολοκληρωθεί
                    if (!"COMPLETED".equals(job.getStatus()) || job.getAnsibleZip() == null) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build();
                    }

                    // Προετοιμασία των Headers για το ZIP αρχείο
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    // Δίνουμε ένα όνομα στο αρχείο που κατεβαίνει
                    headers.setContentDispositionFormData("attachment", "vortex-ansible-" + analysisJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getAnsibleZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Status endpoint - Επιστρέφει την κατάσταση της παραγωγής.
     */
    @GetMapping("/status/{ansibleJobId}")
    public ResponseEntity<String> getStatus(@PathVariable String ansibleJobId, Authentication auth) {
        String userId = auth.getName();
        return ansibleJobRepository.findById(ansibleJobId)
                .map(job -> {
                    // Μόνο ο ιδιοκτήτης του job μπορεί να δει το status
                    if (!job.getUserId().equals(userId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<String>build();
                    }
                    return ResponseEntity.ok(job.getStatus());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}