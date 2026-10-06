package com.somepro.infrastructure.persistence.task.converter;

import com.somepro.domain.task.model.PatrolTask;
import com.somepro.infrastructure.persistence.task.po.PatrolTaskPO;

/**
 * PatrolTaskPO（表）↔ PatrolTask（领域）转换器（基础设施层）。
 *
 * obs_count / abnormal_count / started_at / finished_at 由开工与回报完成环节写入，
 * 这里照常双向搬运；守卫式流转（开工/收尾）走仓储适配器里的稀疏 PO，不经过本转换器。
 */
public final class PatrolTaskPoConverter {

    private PatrolTaskPoConverter() {
    }

    public static PatrolTaskPO toPo(PatrolTask domain) {
        PatrolTaskPO po = new PatrolTaskPO();
        po.setId(domain.getId());
        po.setTaskNo(domain.getTaskNo());
        po.setStationId(domain.getStationId());
        po.setSiteId(domain.getSiteId());
        po.setPatrolType(domain.getPatrolType());
        po.setPlannedDate(domain.getPlannedDate());
        po.setExecutor(domain.getExecutor());
        po.setStatus(domain.getStatus());
        po.setObsCount(domain.getObsCount());
        po.setAbnormalCount(domain.getAbnormalCount());
        po.setStartedAt(domain.getStartedAt());
        po.setFinishedAt(domain.getFinishedAt());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static PatrolTask toDomain(PatrolTaskPO po) {
        PatrolTask domain = new PatrolTask();
        domain.setId(po.getId());
        domain.setTaskNo(po.getTaskNo());
        domain.setStationId(po.getStationId());
        domain.setSiteId(po.getSiteId());
        domain.setPatrolType(po.getPatrolType());
        domain.setPlannedDate(po.getPlannedDate());
        domain.setExecutor(po.getExecutor());
        domain.setStatus(po.getStatus());
        domain.setObsCount(po.getObsCount());
        domain.setAbnormalCount(po.getAbnormalCount());
        domain.setStartedAt(po.getStartedAt());
        domain.setFinishedAt(po.getFinishedAt());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
