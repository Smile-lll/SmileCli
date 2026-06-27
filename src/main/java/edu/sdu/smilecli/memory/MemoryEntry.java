package edu.sdu.smilecli.memory;

import java.nio.file.Path;
import java.util.UUID;

public record MemoryEntry(
        String id,
        String content,
        String scope,       // project 或 global
        String projectPath,
        long createdTime    //时间戳
) {
    public MemoryEntry(String content, String scope) {
        this("mem-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                scope,
                Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .toString(),
                System.currentTimeMillis());
    }
}