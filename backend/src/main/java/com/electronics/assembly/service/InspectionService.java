package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.common.LockRetryExecutor;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 质检与返修业务。
 *
 * <p>核心规则：① 不良点数超过 {@link Dict#MAX_DEFECT_POINTS} 自动判废，
 * 有缺陷但未超阈值判返修，无缺陷判合格；② 判返修的必须走完返修闭环才能放行；
 * ③ 抽检板数必须大于 0。</p>
 *
 * <p>返修闭环状态机：登记返修质检（repairRound = N）→ 对应工序游标置「返修中」
 * → 操作员 {@link #repair(Long, String)} 完成返修，记录转「待复验」→
 * 只能由质检员 {@link #reinspect(Long, Boolean, String, String)} 复验：
 * 通过则闭环（repairDone=true，工序推进到「已完成」），不通过则本轮记「复验不合格」
 * 并自动开启第 N+1 轮返修任务，游标保持返修中。</p>
 *
 * <p>幂等与并发口径（与换线模块一致）：所有返修写操作先按工单 id 悲观锁工单行，
 * 再锁工序游标行 / 质检行串行化；同一工单同一工序存在未完成返修时，重复登记
 * 一律返回同一条质检记录（不新增任务、不重复加轮次）；锁等待超时用全新事务
 * 自动重试，重试时走幂等分支；数据库另有 uk_insp_repair_round 唯一索引
 * （(order_id, step_name, repair_round) 生成列）兜底——重复点击 / 网络重试 /
 * 并发提交都不会产生重复返修任务，不同工单各走各的锁不串单。</p>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final WorkOrderRepository orderRepository;
    private final SmtStepRepository stepRepository;
    private final LockRetryExecutor lockRetry;

    public InspectionService(InspectionRepository inspectionRepository,
                             WorkOrderRepository orderRepository,
                             SmtStepRepository stepRepository,
                             LockRetryExecutor lockRetry) {
        this.inspectionRepository = inspectionRepository;
        this.orderRepository = orderRepository;
        this.stepRepository = stepRepository;
        this.lockRetry = lockRetry;
    }

    @Transactional(readOnly = true)
    public List<Inspection> list(Long orderId, String verdict) {
        if (orderId != null) {
            return inspectionRepository.findByOrderId(orderId);
        }
        if (verdict != null && !verdict.isBlank()) {
            return inspectionRepository.findByVerdict(verdict);
        }
        return inspectionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Inspection get(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BizException("质检记录不存在：" + id));
    }

    /** 悲观锁工单行；等锁超时（只回滚当前语句）立即转为可重试异常，终止后续无锁逻辑 */
    private WorkOrder lockOrder(Long orderId) {
        try {
            return orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new BizException("工单不存在：" + orderId));
        } catch (org.springframework.dao.PessimisticLockingFailureException
                | org.springframework.dao.QueryTimeoutException e) {
            throw new com.electronics.assembly.common.LockTimeoutException(
                    "工单 " + orderId + " 正被其他写操作锁定，等待超时", e);
        }
    }

    /**
     * 登记一次质检，结论按不良点数自动判定。写操作在独立事务内执行并带锁超时重试。
     */
    public Inspection create(Inspection input) {
        return lockRetry.execute(() -> doCreate(input));
    }

    private Inspection doCreate(Inspection input) {
        if (input.getOrder() == null || input.getOrder().getId() == null) {
            throw new BizException("必须指定所属工单");
        }
        // 并发口径：先锁工单行，同一工单的质检/返修写在此串行化，不同工单互不阻塞
        WorkOrder order = lockOrder(input.getOrder().getId());
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
        String inspector = input.getInspector() == null || input.getInspector().isBlank()
                ? "未署名" : input.getInspector().trim();
        String verdict = Dict.decideVerdict(points);

        // 合格 / 判废只做登记，不驱动返修状态机（判废由 blocking 放行口径拦截）
        if (!"返修".equals(verdict)) {
            return inspectionRepository.save(
                    newPlainInspection(order, input, points, verdict, inspector));
        }
        return openRepairRound(order, input.getStepName(), input.getDefectType(),
                points, input.getSampleQty(), inspector);
    }

    /**
     * 返修判罚核心（调用方已持有工单锁）：幂等复用在修轮次，或开启新一轮返修。
     */
    private Inspection openRepairRound(WorkOrder order, String stepName, String defectType,
                                       int points, int sampleQty, String inspector) {
        Long orderId = order.getId();
        // 锁工序游标行，避免与开工/完工并发覆盖游标
        SmtStep step = lockStep(order, stepName);
        if ("待执行".equals(step.getStatus())) {
            throw new BizException("工序「" + stepName + "」尚未开工，不能登记质检不合格");
        }

        // 幂等：已有未完成返修时返回同一条记录（重复点击 / 网络重试 / 并发提交）
        Inspection open = findOpenRepair(orderId, stepName);
        if (open != null) {
            return open;
        }

        int nextRound = inspectionRepository.maxRepairRound(orderId, stepName) + 1;
        Inspection insp = new Inspection();
        insp.setOrder(order);
        insp.setStepName(stepName);
        insp.setDefectType(defectType);
        insp.setDefectPoints(points);
        insp.setSampleQty(sampleQty);
        insp.setVerdict("返修");
        insp.setInspector(inspector);
        insp.setRepairDone(false);
        insp.setCheckedAt(LocalDateTime.now());
        insp.setRepairRound(nextRound);
        insp.setRepairStatus(Dict.REPAIR_OPEN);
        insp = saveRepairRoundInsp(insp, orderId, stepName, nextRound);

        // 游标置返修中（进行中 / 已完成 都可能被新一轮返修打回），轮次对齐
        if (!Dict.canTransferStep(step.getStatus(), Dict.REPAIR_OPEN)
                && !Dict.REPAIR_OPEN.equals(step.getStatus())) {
            throw new BizException("工序「" + stepName + "」当前为「" + step.getStatus()
                    + "」，不能判返修");
        }
        step.setStatus(Dict.REPAIR_OPEN);
        step.setRepairRound(nextRound);
        stepRepository.save(step);
        return insp;
    }

    /**
     * 插入返修记录，唯一索引冲突时复用并发事务已创建的同轮记录（幂等兜底）。
     */
    private Inspection saveRepairRoundInsp(Inspection insp, Long orderId, String stepName, int round) {
        try {
            return inspectionRepository.saveAndFlush(insp);
        } catch (DataIntegrityViolationException e) {
            Inspection existing = inspectionRepository
                    .findByOrderIdAndStepNameAndRepairRoundAndVerdict(orderId, stepName, round, "返修")
                    .orElse(null);
            if (existing != null) {
                return existing;
            }
            // 当前事务快照不可见（锁等待语句级回滚场景）：抛锁超时让外层整事务重试
            throw new com.electronics.assembly.common.LockTimeoutException(
                    "同轮返修已被并发事务创建，按幂等口径重试", e);
        }
    }

    /** 某工单某工序当前未完成的返修记录（返修中 / 待复验），没有返回 null */
    private Inspection findOpenRepair(Long orderId, String stepName) {
        for (Inspection i : inspectionRepository
                .findByOrderIdAndStepNameAndVerdictOrderByRepairRoundDescIdDesc(
                        orderId, stepName, "返修")) {
            if (Dict.isOpenRepairStatus(i.getRepairStatus())) {
                return i;
            }
        }
        return null;
    }

    private Inspection newPlainInspection(WorkOrder order, Inspection input, int points,
                                          String verdict, String inspector) {
        Inspection insp = new Inspection();
        insp.setOrder(order);
        insp.setStepName(input.getStepName());
        insp.setDefectType(input.getDefectType());
        insp.setDefectPoints(points);
        insp.setSampleQty(input.getSampleQty());
        insp.setVerdict(verdict);
        insp.setInspector(inspector);
        // 旧接口口径：非返修记录 repairDone=true，不进入返修闭环
        insp.setRepairDone(true);
        insp.setCheckedAt(LocalDateTime.now());
        insp.setRepairRound(0);
        insp.setRepairStatus(Dict.REPAIR_NA);
        return insp;
    }

    /**
     * 操作员完成返修：返修中 → 待复验。写操作在独立事务内执行并带锁超时重试。
     * 之后只能由质检复验推进，操作员不能自行闭环。重复提交幂等返回原记录。
     */
    public Inspection repair(Long id, String operator) {
        return lockRetry.execute(() -> doRepair(id, operator));
    }

    /** 悲观锁质检行，等锁超时立即转可重试异常 */
    private Inspection lockInspection(Long id) {
        try {
            return inspectionRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("质检记录不存在：" + id));
        } catch (org.springframework.dao.PessimisticLockingFailureException
                | org.springframework.dao.QueryTimeoutException e) {
            throw new com.electronics.assembly.common.LockTimeoutException(
                    "质检记录 " + id + " 正被其他写操作锁定，等待超时", e);
        }
    }

    /** 悲观锁工序游标行，等锁超时立即转可重试异常 */
    private SmtStep lockStep(WorkOrder order, String stepName) {
        try {
            return stepRepository.findByOrderIdAndStepNameForUpdate(order.getId(), stepName)
                    .orElseThrow(() -> new BizException("工单「" + order.getOrderNo()
                            + "」还没有「" + stepName + "」工序，请先投产并开工"));
        } catch (org.springframework.dao.PessimisticLockingFailureException
                | org.springframework.dao.QueryTimeoutException e) {
            throw new com.electronics.assembly.common.LockTimeoutException(
                    "工序「" + stepName + "」正被其他写操作锁定，等待超时", e);
        }
    }

    private Inspection doRepair(Long id, String operator) {
        Inspection probe = get(id);
        lockOrder(probe.getOrder().getId());
        Inspection insp = lockInspection(id);
        if (!"返修".equals(insp.getVerdict())) {
            throw new BizException("只有「返修」结论需要返修，当前结论：" + insp.getVerdict());
        }
        // 幂等：已经提交过返修完成（待复验 / 已闭环）时原样返回，重复点击不报错也不重复推进
        if (Dict.REPAIR_WAITING.equals(insp.getRepairStatus())
                || Dict.REPAIR_CLOSED.equals(insp.getRepairStatus())) {
            return insp;
        }
        if (Dict.REPAIR_REJECTED.equals(insp.getRepairStatus())) {
            throw new BizException("第 " + insp.getRepairRound()
                    + " 轮返修已被复验驳回，请处理最新一轮返修任务");
        }
        if (!Dict.REPAIR_OPEN.equals(insp.getRepairStatus())) {
            throw new BizException("返修记录当前状态为「" + insp.getRepairStatus() + "」，不能提交返修完成");
        }
        insp.setRepairStatus(Dict.REPAIR_WAITING);
        insp.setRepairedBy(operator == null || operator.isBlank() ? "未指派" : operator.trim());
        insp.setRepairedAt(LocalDateTime.now());
        return inspectionRepository.save(insp);
    }

    /**
     * 质检复验：返修闭环唯一收口。写操作在独立事务内执行并带锁超时重试。
     *
     * @param passed    true = 复验通过（记录闭环、工序置已完成）；
     *                  false = 复验不通过（本轮记「复验不合格」，自动开启下一轮返修）
     * @param inspector 复验质检员；note 不通过时必填
     */
    public Inspection reinspect(Long id, Boolean passed, String inspector, String note) {
        if (passed == null) {
            throw new BizException("必须指定复验结果 passed（true / false）");
        }
        boolean result = passed;
        return lockRetry.execute(() -> doReinspect(id, result, inspector, note));
    }

    private Inspection doReinspect(Long id, boolean passed, String inspector, String note) {
        Inspection probe = get(id);
        // 同一工单的返修写共用工单行锁，复验与登记返修 / 完成返修串行化
        WorkOrder order = lockOrder(probe.getOrder().getId());
        Inspection insp = lockInspection(id);
        if (!"返修".equals(insp.getVerdict())) {
            throw new BizException("只有「返修」结论需要复验，当前结论：" + insp.getVerdict());
        }
        String who = inspector == null || inspector.isBlank() ? "未署名" : inspector.trim();
        String remark = note == null ? null : note.trim();

        // 幂等：终态记录收到网络重试时原样返回，不重复推进、不重复加轮次
        if (Dict.REPAIR_CLOSED.equals(insp.getRepairStatus())) {
            return insp;
        }
        if (Dict.REPAIR_REJECTED.equals(insp.getRepairStatus())) {
            // 复验不通过的网络重试：返回已由本次驳回自动开启的下一轮返修任务，不会再开一轮
            Inspection next = findOpenRepair(order.getId(), insp.getStepName());
            return next != null && next.getRepairRound() > insp.getRepairRound() ? next : insp;
        }
        if (!Dict.REPAIR_WAITING.equals(insp.getRepairStatus())) {
            throw new BizException("返修尚未完成（当前「" + insp.getRepairStatus()
                    + "」），必须由操作员先提交返修完成，质检员才能复验");
        }

        SmtStep step = lockStep(order, insp.getStepName());

        if (passed) {
            insp.setRepairStatus(Dict.REPAIR_CLOSED);
            insp.setRepairDone(true);
            insp.setReinspectedBy(who);
            insp.setReinspectedAt(LocalDateTime.now());
            insp.setReinspectNote(remark == null || remark.isBlank() ? "复验通过" : remark);
            inspectionRepository.save(insp);

            // 只有复验通过才能把游标推进到已完成；轮次必须对应当前轮次
            step.setStatus("已完成");
            if (step.getRepairRound() == null || step.getRepairRound() < insp.getRepairRound()) {
                step.setRepairRound(insp.getRepairRound());
            }
            step.setFinishedAt(LocalDateTime.now());
            stepRepository.save(step);
            return insp;
        }

        // 复验不通过：本轮终结（不闭环），自动开启下一轮返修任务，游标保持返修中
        if (remark == null || remark.isBlank()) {
            throw new BizException("复验不通过必须填写原因");
        }
        insp.setRepairStatus(Dict.REPAIR_REJECTED);
        insp.setRepairDone(false);
        insp.setReinspectedBy(who);
        insp.setReinspectedAt(LocalDateTime.now());
        insp.setReinspectNote(remark);
        inspectionRepository.saveAndFlush(insp);

        int nextRound = inspectionRepository.maxRepairRound(order.getId(), insp.getStepName()) + 1;
        Inspection next = new Inspection();
        next.setOrder(order);
        next.setStepName(insp.getStepName());
        next.setDefectType(insp.getDefectType());
        next.setDefectPoints(insp.getDefectPoints());
        next.setSampleQty(insp.getSampleQty());
        next.setVerdict("返修");
        next.setInspector(who);
        next.setRepairDone(false);
        next.setCheckedAt(LocalDateTime.now());
        next.setRepairRound(nextRound);
        next.setRepairStatus(Dict.REPAIR_OPEN);
        next = saveRepairRoundInsp(next, order.getId(), insp.getStepName(), nextRound);

        step.setStatus(Dict.REPAIR_OPEN);
        step.setRepairRound(nextRound);
        stepRepository.save(step);
        // 返回最新一轮返修任务，前端可直接刷新到「返修中」
        return next;
    }

    /** 未闭环的返修单（兼容旧数据：repair_status 缺列时按 verdict+repair_done 兜底） */
    @Transactional(readOnly = true)
    public List<Inspection> openRepairs() {
        return inspectionRepository.findByVerdictAndRepairDone("返修", false).stream()
                .filter(i -> Dict.isOpenRepairStatus(i.getRepairStatus()))
                .toList();
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
            if ("返修".equals(i.getVerdict()) && Dict.isOpenRepairStatus(i.getRepairStatus())) {
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

    /**
     * 工单推进前断言：有判废记录或未闭环返修时不允许推进到待检验 / 已完成，
     * 即未完成返修不能跳过该工序。（供 WorkOrderService 调用）
     */
    @Transactional(readOnly = true)
    public void assertNoOpenRepair(Long orderId) {
        int scrapped = 0;
        int open = 0;
        for (Inspection i : inspectionRepository.findByOrderId(orderId)) {
            if ("判废".equals(i.getVerdict())) {
                scrapped++;
            }
            if ("返修".equals(i.getVerdict()) && Dict.isOpenRepairStatus(i.getRepairStatus())) {
                open++;
            }
        }
        if (scrapped > 0) {
            throw new BizException("工单存在 " + scrapped + " 条判废质检记录，不能推进");
        }
        if (open > 0) {
            throw new BizException("工单存在 " + open + " 个未完成返修任务，复验闭环前不能跳过该工序");
        }
    }
}
