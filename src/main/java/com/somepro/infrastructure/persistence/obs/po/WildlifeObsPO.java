package com.somepro.infrastructure.persistence.obs.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * t_wildlife_obs 表的持久化对象（PO，基础设施层）。
 *
 * 只描述「表长什么样」。当前任务模块只读它（回报完成时数观测账），
 * 观测录入的写路径归观测模块自己管。
 */
@Getter
@Setter
@TableName("t_wildlife_obs")
public class WildlifeObsPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("obs_no")
    private String obsNo;

    @TableField("task_id")
    private Long taskId;

    @TableField("site_id")
    private Long siteId;

    @TableField("species_code")
    private String speciesCode;

    @TableField("protection_level")
    private String protectionLevel;

    @TableField("individual_count")
    private Integer individualCount;

    @TableField("health_status")
    private String healthStatus;

    @TableField("observed_at")
    private LocalDateTime observedAt;

    @TableField("recorder")
    private String recorder;
}
