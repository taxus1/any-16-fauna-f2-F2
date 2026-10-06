package com.somepro.domain.task.repository;

import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务聚合的仓储端口：由领域层定义，基础设施层实现。
 */
public interface PatrolTaskRepository {

    /** 派发落库；taskNo 为空时由实现侧按 PT-YYYY-NNNN 生成，非空时按指定编号落库（撞号转业务异常）。 */
    Mono<PatrolTask> create(PatrolTask task);

    /** 按 id 更新任务（编号不改）。 */
    Mono<PatrolTask> update(PatrolTask task);

    /**
     * 开工落库：仅当库里仍是「待执行」时置「执行中」并记开工时刻（守卫式流转）。
     * 守卫条件兜住并发双击：两个人同时开工，只有先到的落库，后到的返回 false、时刻不翻动。
     *
     * @return true 表示这次真正流转了状态
     */
    Mono<Boolean> start(PatrolTask task);

    /**
     * 收尾落库：仅当库里仍是「执行中」时置「已完成」，记完成时刻并回写观测账（守卫式流转）。
     * 守卫条件兜住并发重复回报：只有先到的落库，后到的返回 false、不重复计数。
     *
     * @return true 表示这次真正流转了状态
     */
    Mono<Boolean> complete(PatrolTask task);

    /** 取消：状态置 CANCELLED 并逻辑删除（del_flag=1），名单里不再出现，账仍留在表里。 */
    Mono<Void> cancel(PatrolTask task);

    Mono<PatrolTask> findById(Long id);

    /** 条件分页：站/点/类型/状态/计划日期都可空，全空时返回整份任务。 */
    Mono<PageResult<PatrolTask>> page(int pageNum, int pageSize,
                                      Long stationId, Long siteId, String patrolType,
                                      String status, LocalDate plannedDate);

    /**
     * 某监测点某天还没走完（待执行/执行中）的任务条数 —— 「同点同日不挂两条」的占位校验。
     * 已取消的已销账（del_flag=1）不占位，已完成的也不算没走完。
     *
     * @param excludeId 排除的任务 id（改任务时排除自己），可为 null
     */
    Mono<Long> countUnfinishedBySiteAndDate(Long siteId, LocalDate plannedDate, Long excludeId);
}
