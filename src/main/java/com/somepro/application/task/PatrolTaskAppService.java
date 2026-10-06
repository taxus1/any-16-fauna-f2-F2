package com.somepro.application.task;

import com.somepro.application.task.port.TaskObsTallyPort;
import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.site.model.MonitorSite;
import com.somepro.domain.site.repository.MonitorSiteRepository;
import com.somepro.domain.station.model.MonitorStation;
import com.somepro.domain.station.repository.MonitorStationRepository;
import com.somepro.domain.task.model.PatrolTask;
import com.somepro.domain.task.repository.PatrolTaskRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务应用层：编排巡护任务用例（派发、修改、详情、开工、回报完成、取消、条件分页）。
 *
 * 派发门槛（看两头）：
 * - 站得是在运行的（ACTIVE）：停用/关闭的站不再往那儿派；
 * - 点得是在册的（ACTIVE）：停测/撤掉的点不再往那儿派；
 * - 同一个点同一天只挂一条还没走完的任务：前面那条待执行/执行中就先别派，
 *   等它完了或者撤了再派；已取消的不占位，那天能重派。
 * 改任务时换站/换点/改计划日期，同样要过这三道校验（占位校验排除自己）。
 *
 * 执行与收尾：
 * - 开工只有待执行的任务开得了，回报完成只有执行中的任务报得了；
 * - 手快点两下是幂等空操作：第二下没什么可动的，不重复计数、时刻不翻动；
 * - 收尾时按任务底下的观测记录把账归拢（总条数 + 异常条数）写回任务，
 *   与观测录入模块读同一张账；任务落 DONE 后账即封存，想补观测得另开任务。
 */
@Service
public class PatrolTaskAppService {

    private final PatrolTaskRepository taskRepository;
    private final MonitorStationRepository stationRepository;
    private final MonitorSiteRepository siteRepository;
    private final TaskObsTallyPort obsTallyPort;

    public PatrolTaskAppService(PatrolTaskRepository taskRepository,
                                MonitorStationRepository stationRepository,
                                MonitorSiteRepository siteRepository,
                                TaskObsTallyPort obsTallyPort) {
        this.taskRepository = taskRepository;
        this.stationRepository = stationRepository;
        this.siteRepository = siteRepository;
        this.obsTallyPort = obsTallyPort;
    }

    /** 派发巡护任务：默认待执行；taskNo 留空时由仓储层按 PT-YYYY-NNNN 生成。 */
    public Mono<PatrolTask> dispatch(String taskNo, Long stationId, Long siteId,
                                     String patrolType, LocalDate plannedDate, String executor) {
        if (stationId == null) {
            return Mono.error(new BizException("所属监测站不能为空"));
        }
        if (siteId == null) {
            return Mono.error(new BizException("监测点不能为空"));
        }
        return requireDispatchableStation(stationId)
                .then(requireDispatchableSite(siteId))
                .then(Mono.defer(() -> {
                    PatrolTask task = PatrolTask.create(stationId, siteId, patrolType, plannedDate, executor);
                    task.setTaskNo(normalizeNo(taskNo));
                    return requireSiteDateFree(siteId, task.getPlannedDate(), null)
                            .then(taskRepository.create(task));
                }));
    }

    /**
     * 改任务：站/点/类型/计划日期/执行人传啥改啥。
     * 换站要过「站在运行」校验，换点要过「点在册」校验，
     * 改完后的（点, 计划日期）组合不能撞上别的未完成任务（排除自己）。
     */
    public Mono<PatrolTask> updateTask(Long id, Long stationId, Long siteId, String patrolType,
                                       LocalDate plannedDate, String executor) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    Mono<Void> stationCheck = (stationId != null && !stationId.equals(task.getStationId()))
                            ? requireDispatchableStation(stationId).then()
                            : Mono.empty();
                    Mono<Void> siteCheck = (siteId != null && !siteId.equals(task.getSiteId()))
                            ? requireDispatchableSite(siteId).then()
                            : Mono.empty();
                    return Mono.when(stationCheck, siteCheck)
                            .then(Mono.defer(() -> {
                                task.updateProfile(stationId, siteId, patrolType, plannedDate, executor);
                                return requireSiteDateFree(task.getSiteId(), task.getPlannedDate(), task.getId())
                                        .then(taskRepository.update(task));
                            }));
                });
    }

    public Mono<PatrolTask> detail(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")));
    }

    /**
     * 开工：待执行 -> 执行中，记下开工时刻。
     * 重复开工（手快点两下）是幂等空操作：第二下没什么可动的，开工时刻不翻动；
     * 已完成/已取消的任务开不了工。并发双击由仓储层守卫条件兜底，只流转一次。
     */
    public Mono<PatrolTask> start(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    if (!task.start()) {
                        return Mono.just(task);
                    }
                    return taskRepository.start(task)
                            .flatMap(turned -> turned
                                    ? Mono.just(task)
                                    // 并发下被别人抢先流转了：按库里现状返回（时刻以先到的那下为准）
                                    : taskRepository.findById(id)
                                            .switchIfEmpty(Mono.error(new BizException("巡护任务不存在"))));
                });
    }

    /**
     * 回报完成：执行中 -> 已完成，记下完成时刻，并把这一趟的观测账数清写回任务。
     * 还没开工的报不了完成；重复回报是幂等空操作：第二下不重复计数、完成时刻不翻动。
     */
    public Mono<PatrolTask> complete(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    if (PatrolTask.STATUS_DONE.equals(task.getStatus())) {
                        return Mono.just(task);
                    }
                    task.ensureCompletable();
                    return obsTallyPort.tallyByTaskId(task.getId())
                            .flatMap(tally -> {
                                task.complete(tally.obsCount(), tally.abnormalCount());
                                return taskRepository.complete(task)
                                        .flatMap(turned -> turned
                                                ? Mono.just(task)
                                                // 并发下被别人抢先收尾了：按库里现状返回（账以先到的那下为准）
                                                : taskRepository.findById(id)
                                                        .switchIfEmpty(Mono.error(new BizException("巡护任务不存在"))));
                            });
                });
    }

    /** 取消：状态置已取消并销账（逻辑删除），名单里不再翻出来，账留在表里。 */
    public Mono<Void> cancel(Long id) {
        return taskRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("巡护任务不存在")))
                .flatMap(task -> {
                    task.cancel();
                    return taskRepository.cancel(task);
                });
    }

    /** 条件分页：站/点/类型/状态/计划日期随意拼，全空翻整份任务。 */
    public Mono<PageResult<PatrolTask>> pageTasks(int pageNum, int pageSize,
                                                  Long stationId, Long siteId, String patrolType,
                                                  String status, LocalDate plannedDate) {
        return taskRepository.page(pageNum, pageSize, stationId, siteId, patrolType, status, plannedDate);
    }

    /** 站必须存在且在运行：停用/关闭的站不能再往那儿派任务。 */
    private Mono<MonitorStation> requireDispatchableStation(Long stationId) {
        return stationRepository.findById(stationId)
                .switchIfEmpty(Mono.error(new BizException("所属监测站不存在")))
                .flatMap(station -> {
                    if (MonitorStation.STATUS_CLOSED.equals(station.getStatus())) {
                        return Mono.error(new BizException("监测站已关闭，不能派发巡护任务"));
                    }
                    if (MonitorStation.STATUS_SUSPENDED.equals(station.getStatus())) {
                        return Mono.error(new BizException("监测站已停用，不能派发巡护任务"));
                    }
                    return Mono.just(station);
                });
    }

    /** 点必须存在且在册：停测的点不能再往那儿派任务。 */
    private Mono<MonitorSite> requireDispatchableSite(Long siteId) {
        return siteRepository.findById(siteId)
                .switchIfEmpty(Mono.error(new BizException("监测点不存在")))
                .flatMap(site -> {
                    if (MonitorSite.STATUS_INACTIVE.equals(site.getStatus())) {
                        return Mono.error(new BizException("监测点已停测，不能派发巡护任务"));
                    }
                    return Mono.just(site);
                });
    }

    /** 同点同日占位校验：已有没走完的任务（待执行/执行中）就先别派。 */
    private Mono<Void> requireSiteDateFree(Long siteId, LocalDate plannedDate, Long excludeId) {
        return taskRepository.countUnfinishedBySiteAndDate(siteId, plannedDate, excludeId)
                .flatMap(count -> count > 0
                        ? Mono.<Void>error(new BizException("该监测点当天已有未完成的巡护任务，待其完成或取消后再派"))
                        : Mono.<Void>empty());
    }

    private static String normalizeNo(String taskNo) {
        return (taskNo == null || taskNo.isBlank()) ? null : taskNo.trim();
    }
}
