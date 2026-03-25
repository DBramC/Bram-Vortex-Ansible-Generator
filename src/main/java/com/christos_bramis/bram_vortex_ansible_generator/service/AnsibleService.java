package com.christos_bramis.bram_vortex_ansible_generator.service;

import com.christos_bramis.bram_vortex_ansible_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_ansible_generator.entity.AnsibleJob;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_ansible_generator.repository.AnsibleJobRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class AnsibleService {

    private final AnsibleJobRepository ansibleJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;

    public AnsibleService(AnsibleJobRepository ansibleJobRepository,
                          AnalysisJobRepository analysisJobRepository,
                          ChatModel chatModel) {
        this.ansibleJobRepository = ansibleJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
    }

    public void generateAndSaveAnsible(String ansibleJobId, String analysisJobId, String userId) {
        AnsibleJob job = new AnsibleJob();
        job.setId(ansibleJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        ansibleJobRepository.save(job);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found"));

                String blueprintJson = analysisJob.getBlueprintJson();

                // --- NEW ANSIBLE PROMPT ---
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
                    3. **Environment**: Inject 'configurationSettings' from the blueprint as environment variables in the service file.
                    4. **Networking**: Ensure the firewall allows the 'targetContainerPort'.
                    5. **Modular Design**: Use variables for ports, paths, and versions.

                    OUTPUT FORMAT:
                    - Respond with a SINGLE, VALID JSON object.
                    - No markdown formatting.
    
                    EXPECTED JSON SCHEMA:
                    {
                      "playbook.yml": "<full ansible playbook code>",
                      "inventory.ini": "<ansible inventory with placeholders>",
                      "vars.yml": "<variables extracted from blueprint>",
                      "app.service.j2": "<jinja2 template for systemd service>"
                    }
                    """, blueprintJson);

                var mapOutputConverter = new MapOutputConverter();
                String aiResponse = chatModel.call(prompt + "\n\n" + mapOutputConverter.getFormat());

                String cleanResponse = aiResponse.trim();
                if (cleanResponse.startsWith("```")) {
                    cleanResponse = cleanResponse.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
                }

                Map<String, Object> asFilesRaw = mapOutputConverter.convert(cleanResponse);
                Map<String, String> asFiles = new HashMap<>();
                if (asFilesRaw != null) {
                    asFilesRaw.forEach((k, v) -> asFiles.put(k, String.valueOf(v)));
                }

                byte[] zipBytes = createZipInMemory(asFiles);

                job.setAnsibleZip(zipBytes);
                job.setStatus("COMPLETED");
                ansibleJobRepository.save(job);

            } catch (Exception e) {
                job.setStatus("FAILED");
                ansibleJobRepository.save(job);
            }
        });
    }

    private byte[] createZipInMemory(Map<String, String> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}