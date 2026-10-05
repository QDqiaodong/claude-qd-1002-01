package com.electronics.assembly.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * SMT 工序流转记录。
 *
 * <p>一张工单要依次走完：上料 → 贴片 → 回流焊 → AOI → 目检。
 * 工序状态：待执行 → 进行中 → 已完成；进行中可退回待执行，已完成是终态。
 * 质检对该工序判返修时，游标打回「返修中」，只能由质检复验推进到「已完成」；
 * 复验不通过会开启下一轮返修（{@link #repairRound} 递增），游标保持返修中。</p>
 */
@Entity
@Table(name = "smt_step")
@Getter
@Setter
public class SmtStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：要能接收 {"order":{"id":n}} 入参；WorkOrder 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 上料 / 贴片 / 回流焊 / AOI / 目检 */
    @Column(name = "step_name", nullable = false, length = 16)
    private String stepName;

    /** 待执行 / 进行中 / 返修中 / 已完成 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 当前返修轮次：0 = 未发生返修，每开一轮 +1，复验通过后保留为最终轮次 */
    @Column(name = "repair_round", nullable = false)
    private Integer repairRound = 0;

    /** 所属工单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }
}
