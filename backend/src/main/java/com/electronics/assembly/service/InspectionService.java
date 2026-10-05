package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 质检与返修业务。
 *
 * <p>核心规则：① 不良点数超过 {@link Dict#MAX_DEFECT_POINTS} 自动判废，
 * 有缺陷但未超阈值判返修，无缺陷判合格；② 判返修的必须走完返修闭环才能放行；
 * ③ 抽检板数必须大于 0。</p>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final WorkOrderRepository orderRepository;

    public InspectionService(InspectionRepository inspectionRepository,
                             WorkOrderRepository orderRepository) {
        this.inspectionRepository = inspectionRepository;
        this.orderRepository = orderRepository;
    }

    public List<Inspection> list(Long orderId, String verdict) {
        if (orderId != null) {
            return inspectionRepository.findByOrderId(orderId);
        }
        if (verdict != null && !verdict.isBlank()) {
            return inspectionRepository.findByVerdict(verdict);
        }
        return inspectionRepository.findAll();
    }

    public Inspection get(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BizException("质检记录不存在：" + id));
    }

    /**
     * 登记一次质检，结论按不良点数自动判定。
     */
    @Transactional
    public Inspection create(Inspection input) {
        if (input.getOrder() == null || input.getOrder().getId() == null) {
            throw new BizException("必须指定所属工单");
        }
        WorkOrder order = orderRepository.findById(input.getOrder().getId())
                .orElseThrow(() -> new BizException("工单不存在：" + input.getOrder().getId()));
        if (!Dict.isValidStep(input.getStepName())) {
            throw new BizException("工序不合法：" + input.getStepName());
        }
        if (!Dict.isValidDefectType(input.getDefectType())) {
            throw new BizException("缺陷类型不合法：" + input.getDefectType());
        }
        if (input.getSampleQty() == null || input.getSampleQty() <= 0) {
            throw new BizException("抽检板数必须大于 0");
        }
        int points = input.getDefectPoints() == null ? 0 : input.getDefectPoints();
        if (points < 0) {
            throw new BizException("不良点数不能为负数");
        }

        Inspection insp = new Inspection();
        insp.setOrder(order);
        insp.setStepName(input.getStepName());
        insp.setDefectType(input.getDefectType());
        insp.setDefectPoints(points);
        insp.setSampleQty(input.getSampleQty());
        // 规则①：按不良点数自动判定
        insp.setVerdict(Dict.decideVerdict(points));
        insp.setInspector(input.getInspector() == null ? "未署名" : input.getInspector());
        insp.setRepairDone(!"返修".equals(insp.getVerdict()));
        insp.setCheckedAt(LocalDateTime.now());
        return inspectionRepository.save(insp);
    }

    /** 返修闭环 */
    @Transactional
    public Inspection repair(Long id) {
        Inspection insp = get(id);
        if (!"返修".equals(insp.getVerdict())) {
            throw new BizException("只有「返修」结论需要闭环，当前结论：" + insp.getVerdict());
        }
        if (Boolean.TRUE.equals(insp.getRepairDone())) {
            throw new BizException("该返修单已闭环");
        }
        insp.setRepairDone(true);
        return inspectionRepository.save(insp);
    }

    /** 未闭环的返修单 */
    public List<Inspection> openRepairs() {
        return inspectionRepository.findByVerdictAndRepairDone("返修", false);
    }

    /**
     * 某工单是否存在放行阻塞：有判废记录，或有未闭环的返修单。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> blocking(Long orderId) {
        List<Inspection> all = inspectionRepository.findByOrderId(orderId);
        int scrapped = 0;
        int open = 0;
        for (Inspection i : all) {
            if ("判废".equals(i.getVerdict())) {
                scrapped++;
            }
            if ("返修".equals(i.getVerdict()) && !Boolean.TRUE.equals(i.getRepairDone())) {
                open++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("scrapped", scrapped);
        out.put("openRepairs", open);
        out.put("blocked", scrapped > 0 || open > 0);
        return out;
    }
}
