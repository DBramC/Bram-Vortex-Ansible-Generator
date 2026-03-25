package com.christos_bramis.bram_vortex_ansible_generator.controller;

import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.service.AnsibleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/ansible")
public class Ansible {

    private final AnsibleService ansibleService;
    private final AnsibleJobRepository ansibleJobRepository;

    // Constructor Injection για τα απαραίτητα beans
    public Ansible(AnsibleService ansibleService, AnsibleJobRepository ansibleJobRepository) {
        this.ansibleService = ansibleService;
        this.ansibleJobRepository = ansibleJobRepository;
    }

    /**
     * Endpoint που δέχεται το Webhook από τον Repo Analyzer.
     * Ξεκινάει την επικοινωνία με το AI και τη δημιουργία του ZIP.
     */
    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generateAnsible(@PathVariable String analysisJobId, @RequestParam String userId) {
        System.out.println("🚀 [CONTROLLER] Webhook received from Analyzer for Job: " + analysisJobId);

        try {
            // Δημιουργούμε ένα μοναδικό ID για τη συγκεκριμένη παραγωγή Ansible
            String ansibleJobId = UUID.randomUUID().toString();

            // Καλούμε το Service (το οποίο τρέχει Async, οπότε δεν μπλοκάρουμε τον Analyzer)
            ansibleService.generateAndSaveAnsible(ansibleJobId, analysisJobId, userId);

            // Επιστρέφουμε το ID στον Analyzer (ή στο frontend) για να ξέρουν πώς να το αναζητήσουν
            return ResponseEntity.ok(ansibleJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    /**
     * Endpoint για το κατέβασμα του παραγόμενου ZIP από τον χρήστη.
     */
    @GetMapping("/download/{ansibleJobId}")
    public ResponseEntity<byte[]> downloadAnsible(@PathVariable String ansibleJobId, @RequestParam String userId) {
        System.out.println("📦 [CONTROLLER] Download request for AS Job: " + ansibleJobId);

        // 1. Αναζήτηση στη βάση
        return ansibleJobRepository.findById(ansibleJobId)
                .map(job -> {
                    // 2. SECURITY CHECK: Ανήκει αυτό το Job στον χρήστη που το ζητάει;
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] User " + userId + " tried to access unauthorized job: " + ansibleJobId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // 3. CHECK STATUS: Είναι έτοιμο το αρχείο;
                    if (!"COMPLETED".equals(job.getStatus()) || job.getAnsibleZip() == null) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build(); // 202 Accepted (σημαίνει "ακόμα δουλεύω")
                    }

                    // 4. PREPARE DOWNLOAD: Φτιάχνουμε τα headers για τον browser
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-ansible-" + ansibleJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getAnsibleZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Προαιρετικό: Endpoint για να βλέπει το frontend το status (GENERATING, COMPLETED, FAILED)
     */
    @GetMapping("/status/{ansibleJobId}")
    public ResponseEntity<String> getStatus(@PathVariable String ansibleJobId) {
        return ansibleJobRepository.findById(ansibleJobId)
                .map(job -> ResponseEntity.ok(job.getStatus()))
                .orElse(ResponseEntity.notFound().build());
    }
}