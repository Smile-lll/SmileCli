package edu.sdu.smilecli.plan;

import java.util.*;

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
    private final String goal;           // 计划目标 (即用户和LLM的原始输入)
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

    /**
     * 添加任务
     * */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        //加入了新任务 如果旧任务中有被此任务依赖的话，需要更新旧任务的dependents
        //此任务中的 依赖和被依赖List在加入之前应该初始化好
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null)
//                dep.getDependents().add(task.getId()); 简单情况这样写 但是这样没避免反复添加 应该判断原先List是否存在
                dep.addDependent(task.getId());//addDependent和addDependency加了封装

            //注意这里的dep.getDependencies()在Task是final的，
            //但是不能修改的是这个个引用，不是List内容不能修改
        }
    }

    /**
     * 获取任务
     */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /**
     * 获取所有任务
     */
    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 获取可执行的任务（依赖都已完成）
     */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks)) //传入所有任务，在isExecutable取出t所依赖任务
                .toList();
    }

    /**
     * 获取执行顺序
     */
    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    /**
     * 计算执行顺序存入类变量executionOrder
     */
    private boolean falg = false;//标识是否有环 false表示无环 true表示有环
    HashMap<String, Integer> visited = new HashMap<>();//0表示还没遍历到 1表示正在遍历  2表示遍历完成
    public boolean computeExecutionOrder() {
        falg = false;
        visited.clear();
        executionOrder.clear();//防止未来对于一个旧的计划重复计算顺序的时候旧计划中的顺序被保留  目前（2026.6.25）来看可以不用
        for (Task task : tasks.values()) {
            visited.put(task.getId(), 0);
        }
        for (Task task : tasks.values()) {
            if (visited.get(task.getId()) == 0) {
                dfs(task);
            }
        }
        if(falg){
            return false;
        }else{
            return true;
        }
    }

    private void dfs(Task task) {
        if (visited.get(task.getId()) == 1) {
            falg = true;
            return;
        } else if (visited.get(task.getId()) == 2) {
            return;
        }

        visited.put(task.getId(), 1);
        for (String depId : task.getDependents()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dfs(dep);
            }
        }
        visited.put(task.getId(), 2);
        executionOrder.add(0, task.getId());//倒叙插入 顺序执行
    }

    /**
     * 可视化计划
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔═══════════════════════════════════════════════════════════\n");
        sb.append(String.format("║  执行计划: %-46s%n", goal.length() > 46 ? goal.substring(0, 43) + "..." : goal));
        sb.append("╠═══════════════════════════════════════════════════════════\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" :
                    String.join(",", task.getDependencies());

            sb.append(String.format("║  %d. %s %-20s ", i + 1, statusIcon, task.getId()));
            sb.append(String.format("[%-10s] 依赖: %-15s%n",
                    task.getType(), deps));
            String desc = task.getDescription().length() > 50
                    ? task.getDescription().substring(0, 47) + "..."
                    : task.getDescription();
            sb.append(String.format("║     %-53s%n", desc));
        }

        sb.append("╚═══════════════════════════════════════════════════════════\n");
        sb.append(String.format("   进度: %.0f%% | 状态: %s%n",
                getProgress() * 100, status));

        return sb.toString();
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    /**
     * 获取执行进度
     */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }
}
