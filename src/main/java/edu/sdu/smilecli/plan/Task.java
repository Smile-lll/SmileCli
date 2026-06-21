//package edu.sdu.smilecli.plan;
//
//import java.util.List;
//
//public class Task {
//
//    /**
//     * 任务类型定义了 6 种：
//     * PLANNING：规划任务，用于分析和决策
//     * FILE_READ：读取文件，获取信息
//     * FILE_WRITE：写入文件，输出结果
//     * COMMAND：执行命令，编译运行等
//     * ANALYSIS：分析结果，中间决策
//     * VERIFICATION：验证结果，检查正确性
//     *
//     * 任务状态有 5 种：
//     * PENDING：等待执行
//     * RUNNING：执行中
//     * COMPLETED：已完成
//     * FAILED：执行失败
//     * SKIPPED：被跳过（依赖失败）
//     */
//
//    private final String id;              // 任务唯一标识
//    private final String description;     // 任务描述
//    private final TaskType type;          // 任务类型
//    private TaskStatus status;            // 执行状态
//    private String result;                // 执行结果
//    private String error;                 // 错误信息
//    private final List dependencies;  // 依赖的任务ID
//    private final List dependents;    // 被依赖的任务ID
//    private long startTime;               // 开始时间
//    private long endTime;                 // 结束时间
//}
