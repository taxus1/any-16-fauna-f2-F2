package com.somepro.interfaces.rest.task.vo;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 巡护任务对外返回对象（VO，用户接口层）—— 不可变 record。任务编号 taskNo 必带。
 * 进度相关字段一并带出：状态、开工/完成时刻、观测账（obsCount/abnormalCount）。
 */
public record PatrolTaskVO(Long id, String taskNo, Long stationId, Long siteId, String patrolType,
                           LocalDate plannedDate, String executor, String status,
                           Integer obsCount, Integer abnormalCount,
                           LocalDateTime startedAt, LocalDateTime finishedAt,
                           LocalDateTime createTime) implements Serializable {
}
