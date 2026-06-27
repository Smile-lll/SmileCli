package edu.sdu.smilecli.memory;

public record MemoryEntry(
        String id,
        String content,
        String scope,       // project 或 global
        String projectPath,
        long createdTime    //时间戳
) {

}