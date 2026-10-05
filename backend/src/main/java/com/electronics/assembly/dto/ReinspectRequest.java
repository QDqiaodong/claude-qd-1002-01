package com.electronics.assembly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 质检复验入参：质检员对「待复验」的返修任务做最终判定。
 *
 * <p>passed=true 复验通过（闭环，工序置已完成）；
 * passed=false 复验不通过（note 必填，自动开启下一轮返修）。</p>
 */
@Getter
@Setter
public class ReinspectRequest {

    /** 复验是否通过 */
    private Boolean passed;

    /** 复验质检员 */
    private String inspector;

    /** 复验备注 / 不通过原因（不通过时必填） */
    private String note;
}
