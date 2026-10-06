package com.somepro.interfaces.rest.task.converter;

import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.task.vo.PatrolTaskVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PatrolTask（领域）→ PatrolTaskVO（对外）转换器（用户接口层）。
 */
public final class PatrolTaskVoConverter {

    private PatrolTaskVoConverter() {
    }

    public static PatrolTaskVO toVo(PatrolTask task) {
        return new PatrolTaskVO(task.getId(), task.getTaskNo(), task.getStationId(), task.getSiteId(),
                task.getPatrolType(), task.getPlannedDate(), task.getExecutor(), task.getStatus(),
                task.getObsCount(), task.getAbnormalCount(), task.getStartedAt(), task.getFinishedAt(),
                task.getCreateTime());
    }

    public static PageVO<PatrolTaskVO> toPageVo(PageResult<PatrolTask> page) {
        List<PatrolTaskVO> content = page.content().stream()
                .map(PatrolTaskVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
