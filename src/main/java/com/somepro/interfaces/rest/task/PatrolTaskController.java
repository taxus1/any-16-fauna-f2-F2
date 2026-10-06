package com.somepro.interfaces.rest.task;

import com.somepro.application.task.PatrolTaskAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.task.converter.PatrolTaskVoConverter;
import com.somepro.interfaces.rest.task.vo.PatrolTaskVO;
import com.somepro.interfaces.rest.task.vo.TaskCreateRequest;
import com.somepro.interfaces.rest.task.vo.TaskUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 巡护任务接口（用户接口层）：只做协议适配与 VO 转换，业务编排交给应用层。
 *
 * 任务分页：站/点/类型/状态/计划日期条件都可空，全空时翻整份任务；每行都带任务编号。
 * 开工 / 回报完成返回最新任务（含状态、开工/完成时刻、观测账），重复点击是幂等空操作。
 */
@RestController
@RequestMapping("/api/tasks")
public class PatrolTaskController {

    private final PatrolTaskAppService taskAppService;

    public PatrolTaskController(PatrolTaskAppService taskAppService) {
        this.taskAppService = taskAppService;
    }

    /** 派发巡护任务：编号可留空由服务端生成，默认待执行。 */
    @PostMapping
    public Mono<Result<PatrolTaskVO>> dispatch(@Valid @RequestBody TaskCreateRequest req) {
        return taskAppService.dispatch(req.taskNo(), req.stationId(), req.siteId(),
                        req.patrolType(), req.plannedDate(), req.executor())
                .map(PatrolTaskVoConverter::toVo)
                .map(Result::ok);
    }

    @GetMapping("/{id}")
    public Mono<Result<PatrolTaskVO>> detail(@PathVariable Long id) {
        return taskAppService.detail(id)
                .map(PatrolTaskVoConverter::toVo)
                .map(Result::ok);
    }

    /** 改任务：站/点/类型/计划日期/执行人传啥改啥；已完成的任务不能再改。 */
    @PutMapping("/{id}")
    public Mono<Result<PatrolTaskVO>> update(@PathVariable Long id,
                                             @RequestBody TaskUpdateRequest req) {
        return taskAppService.updateTask(id, req.stationId(), req.siteId(), req.patrolType(),
                        req.plannedDate(), req.executor())
                .map(PatrolTaskVoConverter::toVo)
                .map(Result::ok);
    }

    /** 开工：待执行 -> 执行中，记开工时刻；重复开工是幂等空操作，时刻不翻动。 */
    @PostMapping("/{id}/start")
    public Mono<Result<PatrolTaskVO>> start(@PathVariable Long id) {
        return taskAppService.start(id)
                .map(PatrolTaskVoConverter::toVo)
                .map(Result::ok);
    }

    /** 回报完成：执行中 -> 已完成，记完成时刻并把观测账写回任务；重复回报是幂等空操作。 */
    @PostMapping("/{id}/complete")
    public Mono<Result<PatrolTaskVO>> complete(@PathVariable Long id) {
        return taskAppService.complete(id)
                .map(PatrolTaskVoConverter::toVo)
                .map(Result::ok);
    }

    /** 取消：状态置已取消并销账（逻辑删除），名单里不再出现，账留在表里。 */
    @PostMapping("/{id}/cancel")
    public Mono<Result<Void>> cancel(@PathVariable Long id) {
        return taskAppService.cancel(id)
                .then(Mono.just(Result.ok()));
    }

    /** 任务分页：stationId/siteId/patrolType/status/plannedDate 条件随意拼，全空翻整份任务。 */
    @GetMapping({"", "/list"})
    public Mono<Result<PageVO<PatrolTaskVO>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String patrolType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedDate) {
        return taskAppService.pageTasks(pageNum, pageSize, stationId, siteId, patrolType, status, plannedDate)
                .map(PatrolTaskVoConverter::toPageVo)
                .map(Result::ok);
    }
}
