package edu.sdu.smilecli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Task {

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public TaskType getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public List<String> getDependents() {
        return dependents;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    /**
     * 任务类型定义了 6 种：
     * PLANNING：规划任务，用于分析和决策
     * FILE_READ：读取文件，获取信息
     * FILE_WRITE：写入文件，输出结果
     * COMMAND：执行命令，编译运行等
     * ANALYSIS：分析结果，中间决策
     * VERIFICATION：验证结果，检查正确性
     *
     * 任务状态有 5 种：
     * PENDING：等待执行
     * RUNNING：执行中
     * COMPLETED：已完成
     * FAILED：执行失败
     * SKIPPED：被跳过（依赖失败）
     */

    private final String id;              // 任务唯一标识
    private final String description;     // 任务描述
    private final TaskType type;          // 任务类型
    private TaskStatus status;            // 执行状态
    private String result;                // 执行结果
    private String error;                 // 错误信息
    private final List<String> dependencies;      // 依赖的任务ID
    private final List<String> dependents;        // 被依赖的任务ID -> 其他任务依赖该任务
    private long startTime;               // 开始时间
    private long endTime;                 // 结束时间

    public enum TaskType {
        PLANNING,      // 规划任务
        FILE_READ,     // 读取文件
        FILE_WRITE,    // 写入文件
        COMMAND,       // 执行命令
        ANALYSIS,      // 分析结果
        VERIFICATION   // 验证结果
    }

    public enum TaskStatus {
        PENDING,       // 等待执行
        RUNNING,       // 执行中
        COMPLETED,     // 已完成
        FAILED,        // 失败
        SKIPPED        // 跳过
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime; // 防止还没结束
        return endTime - startTime;
    }

    /**
     * 当前是否可以执行（其所有依赖都已完成）
     *
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

}
