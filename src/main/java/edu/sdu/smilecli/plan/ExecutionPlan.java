package edu.sdu.smilecli.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行计划本身也有状态：
 * CREATED：刚创建，还没开始执行
 * RUNNING：正在执行中
 * COMPLETED：所有任务都完成
 * FAILED：有任务失败
 * CANCELLED：被取消
 *
 * 也有id
 * */

public class ExecutionPlan {

    public String getId() {
        return id;
    }

    public String getGoal() {
        return goal;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    private final String id;
    private final String goal;           // 计划目标
    private final Map<String, Task> tasks;  // 所有任务
    private final List<String> executionOrder;  // 执行顺序
    private PlanStatus status;
    private String summary;
    private long startTime;
    private long endTime;


    public enum PlanStatus {
        CREATED,      // 刚创建
        RUNNING,      // 执行中
        COMPLETED,    // 全部完成
        FAILED,       // 有任务失败
        CANCELLED     // 被取消
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();  // 保持插入顺序
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 标记完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 标记失败
     */
    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取总耗时
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }


}
