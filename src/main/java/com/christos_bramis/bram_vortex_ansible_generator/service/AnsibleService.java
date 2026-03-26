package com.christos_bramis.bram_vortex_ansible_generator.service;

import com.christos_bramis.bram_vortex_ansible_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_ansible_generator.entity.AnsibleJob;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

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

    public void generateAndSaveAnsible(String ansibleJobId, String analysisJobId, String userId) {
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
                    Generate a PRODUCTION-READY Ansible structure to deploy a Spring Boot application on a Virtual Machine.
                
                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS:
                    1. **OS Setup**: Assume Ubuntu/Debian. Update apt cache and install Java (JDK 21).
                    2. **Application Deployment**: 
                       - Create a dedicated system user.
                       - Setup a directory structure in `/opt/app`.
                       - Generate a Systemd unit file (`app.service`) to manage the JAR.
                    3. **Environment**: Inject configurations as environment variables.
                    4. **Networking**: Open firewall for the target port.

                    OUTPUT FORMAT:
                    - Respond ONLY with a SINGLE, VALID JSON object.
                    - NO markdown blocks (```json).
                    - NO conversational text.
    
                    JSON STRUCTURE:
                    {
                      "playbook.yml": "...",
                      "inventory.ini": "...",
                      "vars.yml": "...",
                      "app.service.j2": "..."
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
                System.out.println("✅ [ANSIBLE] Success! ZIP size: " + zipBytes.length + " bytes.");

            } catch (Exception e) {
                System.err.println("❌ [ANSIBLE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                ansibleJobRepository.save(job);
            }
        });
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