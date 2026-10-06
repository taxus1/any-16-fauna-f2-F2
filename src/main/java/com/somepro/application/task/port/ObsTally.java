package com.somepro.application.task.port;

/**
 * 一趟任务的观测账（值对象）：任务底下观测记录总条数 + 其中异常条数。
 * 回报完成时由应用层数清后交给领域对象写回任务，与观测录入模块读同一张账（t_wildlife_obs）。
 */
public record ObsTally(int obsCount, int abnormalCount) {
}
