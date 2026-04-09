package com.christos_bramis.bram_vortex_ansible_generator.service;

import com.christos_bramis.bram_vortex_ansible_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_ansible_generator.entity.AnsibleJob;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class AnsibleService {

    private final AnsibleJobRepository ansibleJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public AnsibleService(AnsibleJobRepository ansibleJobRepository,
                          AnalysisJobRepository analysisJobRepository,
                          ChatModel chatModel) {
        this.ansibleJobRepository = ansibleJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    public void generateAndSaveAnsible(String ansibleJobId, String analysisJobId, String userId, String token) {
        System.out.println("\n🚀 [VORTEX-ANSIBLE] Starting Generation for Job: " + ansibleJobId);

        AnsibleJob job = new AnsibleJob();
        job.setId(ansibleJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        ansibleJobRepository.save(job);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Fetching Blueprint
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found"));

                // Η ΣΩΣΤΗ ΓΡΑΜΜΗ: Πρόσεξε το .toPrettyString() πριν το ερωτηματικό
                String blueprintJson = analysisJob.getBlueprintJson() != null ?
                        analysisJob.getBlueprintJson() : "{}";

                // 2. AI Dispatch
                String prompt = String.format("""
                    You are a Principal DevOps Engineer and Ansible Specialist.
                    Your objective is to provision a PRODUCTION-READY Docker Host on a Virtual Machine, preparing it for a CI/CD pipeline deployment via GitHub Actions.

                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %%s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & STRICT CONSTRAINTS:
                    1. **OS Setup**: 
                        - Read the required OS from 'deploymentMetadata.osDistro'. 
                        - Update OS caches (e.g., apt, yum) and install prerequisite system packages (curl, ca-certificates).
       
                    2. **Docker Engine (CRITICAL)**: 
                        - Do NOT install application runtimes like Java, Node, or Python. 
                        - Install ONLY Docker Engine (`docker.io` or `docker-ce`) and `docker-compose`.
                        - Ensure the Docker daemon is enabled and started at boot.
       
                    3. **Permissions (CRITICAL for CI/CD)**: 
                        - Read the execution user from 'deploymentMetadata.executionUser'.
                        - Ensure this user exists on the system.
                        - You MUST add this user to the `docker` group. This is mandatory so the external GitHub Actions runner can execute `docker pull` and `docker run` without `sudo`.
       
                    4. **Networking/Security**: 
                        - Configure the firewall (UFW or firewalld) to explicitly allow SSH (port 22) AND the 'targetContainerPort' defined in the JSON.
       
                    5. **Dynamic Inventory**:
                        - Create an `inventory.ini` file. Use a placeholder like `YOUR_INSTANCE_IP` for the host IP. Define the `ansible_user` based on the target OS (e.g., 'ubuntu').

                    OUTPUT FORMAT (CRITICAL):
                        - Respond ONLY with a SINGLE, VALID JSON object.
                        - NO markdown blocks (e.g., no ```json).
                        - NO conversational text.

                    EXPECTED JSON SCHEMA:
                    {
                        "playbook.yml": "<raw ansible yaml>",
                        "inventory.ini": "<raw inventory ini>",
                        "vars.yml": "<raw yaml variables>"
                    }
                    """, blueprintJson);

                System.out.println("🧠 [ANSIBLE] Calling AI...");
                String aiResponse = chatModel.call(prompt);

                // ΔΙΑΓΝΩΣΤΙΚΟ: Βλέπουμε τι ακριβώς έστειλε το AI
                System.out.println("DEBUG AI RAW RESPONSE length: " + (aiResponse != null ? aiResponse.length() : 0));

                // 3. Robust Parsing
                Map<String, String> asFiles = parseResponse(aiResponse);

                if (asFiles == null || asFiles.isEmpty()) {
                    throw new RuntimeException("AI returned empty file set or invalid JSON format");
                }

                // 4. Zipping
                System.out.println("🤐 [ANSIBLE] Creating ZIP for " + asFiles.size() + " files...");
                byte[] zipBytes = createZipInMemory(asFiles);

                if (zipBytes == null || zipBytes.length < 100) {
                    throw new RuntimeException("Generated ZIP is suspiciously small (" + (zipBytes != null ? zipBytes.length : 0) + " bytes)");
                }

                // 5. Finalize
                job.setAnsibleZip(zipBytes);
                job.setStatus("COMPLETED");
                ansibleJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "ANSIBLE", "COMPLETED", token);
                System.out.println("✅ [ANSIBLE] Success! ZIP size: " + zipBytes.length + " bytes.");

            } catch (Exception e) {
                System.err.println("❌ [ANSIBLE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                ansibleJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "ANSIBLE", "COMPLETED", token);

            }
        });
    }

    private void notifyOrchestrator(String jobId, String service, String status, String token) {
        String url = String.format("http://repo-analyzer-svc/dashboard/internal/callback/%s?service=%s&status=%s",
                jobId, service, status);

        RestClient internalClient = RestClient.create();
        internalClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + token) // 👈 Το token επιστρέφει στον Analyzer
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, String> parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            System.err.println("⚠️ [PARSING ERROR] AI response is null or empty");
            return new HashMap<>();
        }

        try {
            String clean = response.trim();
            // Αφαίρεση markdown αν το AI παρακούσει
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
            }

            // Χρήση ObjectMapper για μετατροπή του String σε Map
            return objectMapper.readValue(clean, new TypeReference<HashMap<String, String>>() {});
        } catch (Exception e) {
            System.err.println("⚠️ [PARSING ERROR] Failed to convert AI response to Map: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private byte[] createZipInMemory(Map<String, String> files) throws Exception {
        // Το baos μένει έξω από το try-with-resources του zos, για να το επιστρέψουμε στο τέλος.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;

                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish(); // Οριστικοποίηση του ZIP structure
            zos.flush();
        }
        return baos.toByteArray();
    }
}