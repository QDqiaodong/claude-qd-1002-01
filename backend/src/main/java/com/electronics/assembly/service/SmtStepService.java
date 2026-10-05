package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.repository.SmtStepRepository;

/**
 * SMT 工序流转业务。
 *
 * <p>核心规则：① 工序按「上料 → 贴片 → 回流焊 → AOI → 目检」固定顺序推进，
 * 前一道没完成不能开下一道；② 状态流转：待执行 → 进行中 → 已完成；
 * ③ 已完成的工序不能回退；④ 工单存在未放行的换线批次时，贴片工序
 * 不能开工也不能完工（首件确认前不得批量贴片），已完成的历史工序不受影响。</p>
 */
@Service
public class SmtStepService {

    private final SmtStepRepository stepRepository;
    private final LineChangeService lineChangeService;

    public SmtStepService(SmtStepRepository stepRepository, LineChangeService lineChangeService) {
        this.stepRepository = stepRepository;
        this.lineChangeService = lineChangeService;
    }

    public List<SmtStep> list(Long orderId, String status) {
        if (orderId != null) {
            return stepRepository.findByOrderIdOrderById(orderId);
        }
        if (status != null && !status.isBlank()) {
            return stepRepository.findByStatus(status);
        }
        return stepRepository.findAll();
    }

    public SmtStep get(Long id) {
        return stepRepository.findById(id)
                .orElseThrow(() -> new BizException("工序记录不存在：" + id));
    }

    /**
     * 开始某道工序。
     */
    @Transactional
    public SmtStep start(Long id, String operator) {
        SmtStep step = get(id);
        // 规则①：前序工序必须已完成
        int idx = Dict.stepIndex(step.getStepName());
        if (idx < 0) {
            throw new BizException("工序名称不合法：" + step.getStepName());
        }
        if (idx > 0) {
            String prev = Dict.STEPS.get(idx - 1);
            SmtStep prevStep = stepRepository
                    .findByOrderIdAndStepName(step.getOrder().getId(), prev)
                    .orElseThrow(() -> new BizException("前序工序「" + prev + "」尚未建立"));
            if (!"已完成".equals(prevStep.getStatus())) {
                throw new BizException("前序工序「" + prev + "」尚未完成，当前状态：" + prevStep.getStatus());
            }
        }
        if (!Dict.canTransferStep(step.getStatus(), "进行中")) {
            throw new BizException("工序「" + step.getStepName() + "」当前为「" + step.getStatus() + "」，不能开工");
        }
        // 规则④：换线批次未放行时，阻止工单从上料推进到贴片
        lineChangeService.assertStepReleased(step.getOrder().getId(), step.getStepName(), "开工");
        step.setStatus("进行中");
        step.setOperator(operator == null || operator.isBlank() ? "未指派" : operator);
        step.setStartedAt(LocalDateTime.now());
        return stepRepository.save(step);
    }

    /** 完成工序 */
    @Transactional
    public SmtStep finish(Long id) {
        SmtStep step = get(id);
        if (!Dict.canTransferStep(step.getStatus(), "已完成")) {
            throw new BizException("只有「进行中」的工序能完工，当前状态：" + step.getStatus());
        }
        // 规则④：换线批次未放行时，进行中的贴片也不能完工（首件确认前不得批量贴片）
        lineChangeService.assertStepReleased(step.getOrder().getId(), step.getStepName(), "完工");
        step.setStatus("已完成");
        step.setFinishedAt(LocalDateTime.now());
        return stepRepository.save(step);
    }

    /** 退回待执行（进行中可退） */
    @Transactional
    public SmtStep reset(Long id) {
        SmtStep step = get(id);
        if (!Dict.canTransferStep(step.getStatus(), "待执行")) {
            throw new BizException("只有「进行中」的工序能退回，当前状态：" + step.getStatus());
        }
        step.setStatus("待执行");
        step.setStartedAt(null);
        return stepRepository.save(step);
    }

    /** 某工单的工序进度 */
    public int[] progress(Long orderId) {
        List<SmtStep> steps = stepRepository.findByOrderIdOrderById(orderId);
        int done = 0;
        for (SmtStep s : steps) {
            if ("已完成".equals(s.getStatus())) {
                done++;
            }
        }
        return new int[]{done, steps.size()};
    }
}
