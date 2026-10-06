package com.somepro.infrastructure.persistence.task;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import com.somepro.infrastructure.persistence.task.converter.PatrolTaskPoConverter;
import com.somepro.infrastructure.persistence.task.po.PatrolTaskPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 巡护任务仓储适配器（基础设施层）。
 *
 * 编号分配：taskNo 为空时按 PT-YYYY-NNNN 生成（并发撞号由 {@link BizNoGenerator} 重试）；
 * 指定编号时撞唯一索引转业务异常，不甩底层 DuplicateKeyException。
 * 取消 = 状态置 CANCELLED + 逻辑删除（del_flag=1）：名单翻不到，账留在表里。
 */
@Repository
public class PatrolTaskRepositoryImpl implements PatrolTaskRepository {

    /** 编号前缀：PT-年-（如 PT-2026-） */
    private static final String NO_PREFIX = "PT-";

    private final PatrolTaskMapper taskMapper;

    public PatrolTaskRepositoryImpl(PatrolTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public Mono<PatrolTask> create(PatrolTask task) {
        return blocking(() -> {
            if (task.getTaskNo() != null && !task.getTaskNo().isBlank()) {
                try {
                    return doInsert(task, task.getTaskNo().trim());
                } catch (DuplicateKeyException e) {
                    throw new BizException("巡护任务编号已存在：" + task.getTaskNo());
                }
            }
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> taskMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(task, no));
        });
    }

    @Override
    public Mono<PatrolTask> update(PatrolTask task) {
        return blocking(() -> {
            PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
            taskMapper.updateById(po);
            return PatrolTaskPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> start(PatrolTask task) {
        return blocking(() -> {
            // 守卫式流转：WHERE status = PENDING —— 并发双击只有先到的落库，
            // 后到的 0 行命中、开工时刻不会被改写。审计字段由 MetaObjectHandler 填充。
            PatrolTaskPO po = new PatrolTaskPO();
            po.setStatus(task.getStatus());
            po.setStartedAt(task.getStartedAt());
            int rows = taskMapper.update(po, Wrappers.<PatrolTaskPO>lambdaUpdate()
                    .eq(PatrolTaskPO::getId, task.getId())
                    .eq(PatrolTaskPO::getStatus, PatrolTask.STATUS_PENDING));
            return rows > 0;
        });
    }

    @Override
    public Mono<Boolean> complete(PatrolTask task) {
        return blocking(() -> {
            // 守卫式流转：WHERE status = IN_PROGRESS —— 并发重复回报只有先到的落库，
            // 后到的 0 行命中、观测账与完成时刻不会被改写。
            PatrolTaskPO po = new PatrolTaskPO();
            po.setStatus(task.getStatus());
            po.setFinishedAt(task.getFinishedAt());
            po.setObsCount(task.getObsCount());
            po.setAbnormalCount(task.getAbnormalCount());
            int rows = taskMapper.update(po, Wrappers.<PatrolTaskPO>lambdaUpdate()
                    .eq(PatrolTaskPO::getId, task.getId())
                    .eq(PatrolTaskPO::getStatus, PatrolTask.STATUS_IN_PROGRESS));
            return rows > 0;
        });
    }

    @Override
    public Mono<Void> cancel(PatrolTask task) {
        return blocking(() -> {
            // 先落状态 CANCELLED（审计字段自动填充），再 @TableLogic 置 del_flag=1：
            // UPDATE t_patrol_task SET del_flag = 1 WHERE id = ? AND del_flag = 0
            PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
            taskMapper.updateById(po);
            taskMapper.deleteById(task.getId());
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<PatrolTask> findById(Long id) {
        return blocking(() -> {
            PatrolTaskPO po = taskMapper.selectById(id);
            return po == null ? null : PatrolTaskPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<PatrolTask>> page(int pageNum, int pageSize,
                                             Long stationId, Long siteId, String patrolType,
                                             String status, LocalDate plannedDate) {
        return this.<PageResult<PatrolTask>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<PatrolTaskPO> wrapper = Wrappers.<PatrolTaskPO>lambdaQuery()
                        .eq(stationId != null, PatrolTaskPO::getStationId, stationId)
                        .eq(siteId != null, PatrolTaskPO::getSiteId, siteId)
                        .eq(hasText(patrolType), PatrolTaskPO::getPatrolType, patrolType)
                        .eq(hasText(status), PatrolTaskPO::getStatus, status)
                        .eq(plannedDate != null, PatrolTaskPO::getPlannedDate, plannedDate)
                        .orderByAsc(PatrolTaskPO::getId);
                List<PatrolTaskPO> rows = taskMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<PatrolTask> content = rows.stream()
                        .map(PatrolTaskPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    @Override
    public Mono<Long> countUnfinishedBySiteAndDate(Long siteId, LocalDate plannedDate, Long excludeId) {
        return blocking(() -> taskMapper.selectCount(Wrappers.<PatrolTaskPO>lambdaQuery()
                .eq(PatrolTaskPO::getSiteId, siteId)
                .eq(PatrolTaskPO::getPlannedDate, plannedDate)
                .in(PatrolTaskPO::getStatus, PatrolTask.STATUS_PENDING, PatrolTask.STATUS_IN_PROGRESS)
                .ne(excludeId != null, PatrolTaskPO::getId, excludeId)));
    }

    private PatrolTask doInsert(PatrolTask task, String taskNo) {
        task.setTaskNo(taskNo);
        PatrolTaskPO po = PatrolTaskPoConverter.toPo(task);
        po.setId(IdUtil.getSnowflakeNextId());
        taskMapper.insert(po);
        return PatrolTaskPoConverter.toDomain(po);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 阻塞 DB 调用 → 响应式链路的桥接器：先取 Reactor Context 里的操作人，
     * 再切到 boundedElastic 执行 JDBC，操作人放进 AuditContextHolder 供审计填充。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
