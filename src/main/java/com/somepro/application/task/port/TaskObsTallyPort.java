package com.somepro.application.task.port;

import reactor.core.publisher.Mono;

/**
 * 任务观测账端口（应用层）：回报完成时按任务把底下的观测记录数清楚。
 *
 * 由基础设施层实现，数的是 t_wildlife_obs —— 与观测录入模块同一张账，
 * 任务上回写的 obs_count / abnormal_count 因此和观测侧翻出来的条数对得上。
 * 异常口径：健康状态为受伤（INJURED）/ 死亡（DEAD）/ 疑似疫病（SUSPECT）。
 */
public interface TaskObsTallyPort {

    /** 数清某任务底下的观测账：总条数 + 异常条数。 */
    Mono<ObsTally> tallyByTaskId(Long taskId);
}
