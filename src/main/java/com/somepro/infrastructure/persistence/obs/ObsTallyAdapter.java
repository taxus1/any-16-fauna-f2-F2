package com.somepro.infrastructure.persistence.obs;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.application.task.port.ObsTally;
import com.somepro.application.task.port.TaskObsTallyPort;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Supplier;

/**
 * 任务观测账适配器（基础设施层）：实现应用层 {@link TaskObsTallyPort}。
 *
 * 数的是 t_wildlife_obs 里挂在该任务底下、未销账（del_flag=0，@TableLogic 自动拼）的记录，
 * 与观测录入模块同一张账 —— 任务上回写的条数和观测侧翻出来的条数因此一致。
 * 异常口径：健康状态 INJURED 受伤 / DEAD 死亡 / SUSPECT 疑似疫病。
 */
@Repository
public class ObsTallyAdapter implements TaskObsTallyPort {

    /** 异常健康状态口径（t_wildlife_obs.health_status 列契约）：受伤 / 死亡 / 疑似疫病 */
    private static final List<String> ABNORMAL_HEALTH = List.of("INJURED", "DEAD", "SUSPECT");

    private final WildlifeObsMapper obsMapper;

    public ObsTallyAdapter(WildlifeObsMapper obsMapper) {
        this.obsMapper = obsMapper;
    }

    @Override
    public Mono<ObsTally> tallyByTaskId(Long taskId) {
        return blocking(() -> {
            long total = obsMapper.selectCount(Wrappers.<WildlifeObsPO>lambdaQuery()
                    .eq(WildlifeObsPO::getTaskId, taskId));
            long abnormal = obsMapper.selectCount(Wrappers.<WildlifeObsPO>lambdaQuery()
                    .eq(WildlifeObsPO::getTaskId, taskId)
                    .in(WildlifeObsPO::getHealthStatus, ABNORMAL_HEALTH));
            return new ObsTally((int) total, (int) abnormal);
        });
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
