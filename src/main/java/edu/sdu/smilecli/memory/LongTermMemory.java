package edu.sdu.smilecli.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class LongTermMemory {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storageFile;
    private final List<MemoryEntry> memories = new ArrayList<>();

    public LongTermMemory() {
        this.storageFile = Path.of(
                System.getProperty("user.home"),//默认是本地用户目录下  -> global
                ".smilecli",
                "memory",
                "long_term_memory.json"
        );
        loadFromDisk(); //运行之后的 长期记忆实际维护在List<MemoryEntry> memories中
    }

    public void store(String content) {
        store(content, "project");
    }

    public void store(String content, String scope) {
        if (content == null || content.isBlank()) {
            return;
        }

        String normalizedScope = "global".equalsIgnoreCase(scope)
                ? "global"
                : "project";

        //加入前应该先判断原本有没有
        String projectPath = currentProjectPath();

        boolean exists = memories.stream().anyMatch(memory ->
                sameMemory(memory, content, normalizedScope, projectPath)
        );

        if (exists) {
            return;
        }

        MemoryEntry entry = new MemoryEntry(
                "mem-" + UUID.randomUUID().toString().substring(0, 8),
                content.trim(),
                normalizedScope,
                currentProjectPath(),
                System.currentTimeMillis()
        );

        memories.add(entry);
        saveToDisk();
    }

    public List<MemoryEntry> list() {
        return new ArrayList<>(memories);
    }

    private void loadFromDisk() {
        try {
            if (!Files.exists(storageFile)) {
                return;
            }

            List<MemoryEntry> loaded = mapper.readValue(
                    storageFile.toFile(),
                    new TypeReference<List<MemoryEntry>>() {
                    }
            );

            memories.clear();
            memories.addAll(loaded);
        } catch (Exception e) {
            System.err.println("读取长期记忆失败: " + e.getMessage());
        }
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(storageFile.getParent());//创建父目录
            mapper.writeValue(storageFile.toFile(), memories);//创建当前目录  覆盖写
        } catch (Exception e) {
            log.info("保存长期记忆失败: " + e.getMessage());
        }
    }

    private String currentProjectPath() {
        return Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private boolean sameMemory(
            MemoryEntry memory,
            String content,
            String scope,
            String projectPath
    ) {
        if (!memory.content().equals(content)) {//todo 优化成语义去重
            return false;
        }

        if (!memory.scope().equals(scope)) {
            return false;
        }

        return memory.projectPath().equals(projectPath);
    }
}
