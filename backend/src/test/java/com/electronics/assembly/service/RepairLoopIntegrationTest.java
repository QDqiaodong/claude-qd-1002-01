package com.electronics.assembly.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 「质检不合格 → 返修」闭环集成测试（H2 MySQL 兼容模式）。
 *
 * 覆盖：判返修自动开轮次/游标置返修中、重复登记/重复点击/网络重试幂等、
 * 并发提交不产生重复返修任务、复验通过/不通过、多轮返修、未完成返修不能跳过工序、
 * 不同工单不串单、历史缺字段数据兼容、数据库唯一约束兜底、旧接口字段口径。
 */
@SpringBootTest
@ActiveProfiles("test")
class RepairLoopIntegrationTest {

    @Autowired private InspectionService inspectionService;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private SmtStepService smtStepService;
    @Autowired private InspectionRepository inspectionRepository;
    @Autowired private SmtStepRepository smtStepRepository;
    @Autowired private WorkOrderRepository workOrderRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM inspection");
        jdbc.update("DELETE FROM smt_step");
        jdbc.update("DELETE FROM work_order");
    }

    // ---------------- 夹具 ----------------

    /** 建一张贴片中工单，并铺开五道工序、把 AOI 推进到「进行中」 */
    private Long setupOrderWithRunningStep(String orderNo, String stepName) {
        WorkOrder o = newOrder(orderNo);
        int idx = Dict.stepIndex(stepName);
        for (int i = 0; i < Dict.STEPS.size(); i++) {
            SmtStep s = new SmtStep();
            s.setOrder(o);
            s.setStepName(Dict.STEPS.get(i));
            // 目标工序之前的全部置已完成，目标工序置进行中
            s.setStatus(i < idx ? "已完成" : i == idx ? "进行中" : "待执行");
            if (i < idx) {
                s.setOperator("王强");
            }
            smtStepRepository.save(s);
        }
        return o.getId();
    }

    private WorkOrder newOrder(String orderNo) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo(orderNo);
        o.setProductModel("MODEL-T");
        o.setPlanQty(100);
        o.setDoneQty(0);
        o.setPriority("高");
        o.setStatus("贴片中");
        o.setDueDate(LocalDate.now().plusDays(5));
        o.setCreatedAt(LocalDateTime.now());
        return workOrderRepository.save(o);
    }

    private Inspection repairInspectionInput(Long orderId, String step, int points) {
        Inspection in = new Inspection();
        WorkOrder ref = new WorkOrder();
        ref.setId(orderId);
        in.setOrder(ref);
        in.setStepName(step);
        in.setDefectType("虚焊");
        in.setDefectPoints(points);
        in.setSampleQty(40);
        in.setInspector("质检员周");
        return in;
    }

    private SmtStep stepOf(Long orderId, String name) {
        return smtStepRepository.findByOrderIdAndStepName(orderId, name).orElseThrow();
    }

    private List<Inspection> repairs(Long orderId, String step) {
        return inspectionRepository
                .findByOrderIdAndStepNameAndVerdictOrderByRepairRoundDescIdDesc(orderId, step, "返修");
    }

    // ---------------- 基本闭环 ----------------

    @Test
    void rejectVerdictOpensRoundAndPutsStepIntoRepairing() {
        Long oid = setupOrderWithRunningStep("WO-T1", "AOI");

        Inspection insp = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        assertEquals("返修", insp.getVerdict());
        assertEquals(1, insp.getRepairRound());
        assertEquals(Dict.REPAIR_OPEN, insp.getRepairStatus());
        assertFalse(insp.getRepairDone());
        // 列表 / 详情一致
        assertEquals(1, repairs(oid, "AOI").size());
        assertEquals(Dict.REPAIR_OPEN, inspectionService.get(insp.getId()).getRepairStatus());
        // 游标置返修中，轮次对齐
        SmtStep aoi = stepOf(oid, "AOI");
        assertEquals("返修中", aoi.getStatus());
        assertEquals(1, aoi.getRepairRound());
        // 放行被阻塞
        assertTrue(inspectionService.blocking(oid).get("blocked").equals(true));
    }

    @Test
    void repairThenReinspectPassClosesLoopAndFinishesStep() {
        Long oid = setupOrderWithRunningStep("WO-T2", "AOI");
        Inspection insp = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        // 操作员完成返修 → 待复验，不能自行闭环
        Inspection waiting = inspectionService.repair(insp.getId(), "操作员李");
        assertEquals(Dict.REPAIR_WAITING, waiting.getRepairStatus());
        assertFalse(waiting.getRepairDone());
        assertEquals("操作员李", waiting.getRepairedBy());
        assertNotNull(waiting.getRepairedAt());

        // 复验前游标仍是返修中
        assertEquals("返修中", stepOf(oid, "AOI").getStatus());

        Inspection closed = inspectionService.reinspect(insp.getId(), true, "质检员周", "焊点已修复");
        assertEquals(Dict.REPAIR_CLOSED, closed.getRepairStatus());
        assertTrue(closed.getRepairDone(), "复验通过才允许闭环（旧字段 repairDone 口径）");
        assertEquals("质检员周", closed.getReinspectedBy());
        assertNotNull(closed.getReinspectedAt());

        // 游标只能由复验推进到已完成；列表 / 详情 / blocking 一致
        assertEquals("已完成", stepOf(oid, "AOI").getStatus());
        assertEquals(1, stepOf(oid, "AOI").getRepairRound());
        assertEquals(Dict.REPAIR_CLOSED, inspectionService.get(insp.getId()).getRepairStatus());
        assertFalse(Boolean.TRUE.equals(inspectionService.blocking(oid).get("blocked")));
        assertTrue(inspectionService.openRepairs().stream().noneMatch(i -> i.getId().equals(insp.getId())));
    }

    @Test
    void reinspectFailOpensNextRoundAndKeepsStepRepairing() {
        Long oid = setupOrderWithRunningStep("WO-T3", "AOI");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "AOI", 5));
        inspectionService.repair(r1.getId(), "操作员李");

        // 不通过必须填原因
        BizException ex = assertThrows(BizException.class,
                () -> inspectionService.reinspect(r1.getId(), false, "质检员周", "  "));
        assertTrue(ex.getMessage().contains("原因"));

        Inspection r2 = inspectionService.reinspect(r1.getId(), false, "质检员周", "仍有连锡");
        // 返回的是新一轮返修任务，轮次递增
        assertEquals(2, r2.getRepairRound());
        assertEquals(Dict.REPAIR_OPEN, r2.getRepairStatus());
        assertFalse(r2.getRepairDone());
        // 旧一轮记复验不合格、未闭环
        Inspection first = inspectionService.get(r1.getId());
        assertEquals(Dict.REPAIR_REJECTED, first.getRepairStatus());
        assertFalse(first.getRepairDone());
        assertEquals("仍有连锡", first.getReinspectNote());
        // 游标保持返修中，轮次推进到 2
        SmtStep aoi = stepOf(oid, "AOI");
        assertEquals("返修中", aoi.getStatus());
        assertEquals(2, aoi.getRepairRound());
        // 只有一个在修任务，放行仍阻塞
        assertEquals(1, inspectionService.openRepairs().stream()
                .filter(i -> i.getOrder().getId().equals(oid)).count());

        // 新一轮走完：完成返修 → 复验通过 → 闭环、工序完成
        inspectionService.repair(r2.getId(), "操作员李");
        Inspection closed = inspectionService.reinspect(r2.getId(), true, "质检员周", null);
        assertEquals(Dict.REPAIR_CLOSED, closed.getRepairStatus());
        assertEquals("已完成", stepOf(oid, "AOI").getStatus());
    }

    // ---------------- 幂等 ----------------

    @Test
    void duplicateCreateWhileRepairOpenIsIdempotent() {
        Long oid = setupOrderWithRunningStep("WO-T4", "AOI");
        Inspection first = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        // 返修中重复登记（重复点击 / 网络重试）：返回同一条，不新增、不加轮次
        Inspection again = inspectionService.create(repairInspectionInput(oid, "AOI", 8));
        assertEquals(first.getId(), again.getId());
        assertEquals(1, again.getRepairRound());

        // 操作员完成返修后（待复验）再次登记，仍然复用同一轮
        inspectionService.repair(first.getId(), "操作员李");
        Inspection yetAgain = inspectionService.create(repairInspectionInput(oid, "AOI", 9));
        assertEquals(first.getId(), yetAgain.getId());
        assertEquals(Dict.REPAIR_WAITING, yetAgain.getRepairStatus());

        assertEquals(1, repairs(oid, "AOI").size());
        assertEquals("返修中", stepOf(oid, "AOI").getStatus());
    }

    @Test
    void repairAndReinspectCallsAreIdempotentOnRetry() {
        Long oid = setupOrderWithRunningStep("WO-T5", "AOI");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        // 重复点击「完成返修」
        Inspection w1 = inspectionService.repair(r1.getId(), "操作员李");
        Inspection w2 = inspectionService.repair(r1.getId(), "操作员李");
        assertEquals(r1.getId(), w1.getId());
        assertEquals(r1.getId(), w2.getId());
        assertEquals(Dict.REPAIR_WAITING, w2.getRepairStatus());
        assertEquals("操作员李", w2.getRepairedBy());

        // 复验通过的网络重试：原样返回，不重复推进
        Inspection c1 = inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        Inspection c2 = inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        assertEquals(Dict.REPAIR_CLOSED, c1.getRepairStatus());
        assertEquals(Dict.REPAIR_CLOSED, c2.getRepairStatus());

        // 复验不合格的网络重试：返回已开启的新一轮，不会因为重试再开一轮
        Long oid2 = setupOrderWithRunningStep("WO-T5B", "AOI");
        Inspection r = inspectionService.create(repairInspectionInput(oid2, "AOI", 5));
        inspectionService.repair(r.getId(), "操作员李");
        Inspection n1 = inspectionService.reinspect(r.getId(), false, "质检员周", "不合格");
        assertEquals(2, n1.getRepairRound());
        Inspection retry = inspectionService.reinspect(r.getId(), false, "质检员周", "不合格");
        assertEquals(n1.getId(), retry.getId());
        assertEquals(2, retry.getRepairRound(), "复验不合格重试不得再开新一轮");
        assertEquals(2, repairs(oid2, "AOI").size());
    }

    // ---------------- 并发提交 ----------------

    @Test
    void concurrentCreateProducesSingleRepairTask() throws Exception {
        Long oid = setupOrderWithRunningStep("WO-T6", "AOI");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return inspectionService.create(repairInspectionInput(oid, "AOI", 5)).getId();
            }));
        }
        start.countDown();
        Long firstId = null;
        for (Future<Long> f : futures) {
            Long id = f.get(30, TimeUnit.SECONDS);
            if (firstId == null) {
                firstId = id;
            }
            assertEquals(firstId, id, "并发提交必须全部复用同一条返修质检记录");
        }
        pool.shutdown();

        assertEquals(1, repairs(oid, "AOI").size(), "数据库中只允许一条返修任务");
        assertEquals(1, repairs(oid, "AOI").get(0).getRepairRound());
        assertEquals("返修中", stepOf(oid, "AOI").getStatus());
        assertEquals(1, stepOf(oid, "AOI").getRepairRound());
    }

    @Test
    void concurrentRepairAndReinspectDoNotCorruptState() throws Exception {
        Long oid = setupOrderWithRunningStep("WO-T7", "AOI");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        // 并发：6 个完成返修 + 6 个复验通过，最终状态必须唯一且正确
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            boolean repairCall = t % 2 == 0;
            futures.add(pool.submit(() -> {
                start.await();
                if (repairCall) {
                    inspectionService.repair(r1.getId(), "操作员李");
                } else {
                    // 返修未完成时复验必须失败（不能抢跑）；已完成后复验成功，二者都允许出现
                    try {
                        inspectionService.reinspect(r1.getId(), true, "质检员周", null);
                    } catch (BizException ignore) {
                        // 返修尚未完成的合法拒绝
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 复验通过的前提是返修先完成；完成返修必然成功，所以这里再串行兜底复验一次，
        // 验证状态机最终一定能收敛到闭环
        Inspection cur = inspectionService.get(r1.getId());
        if (!Dict.REPAIR_CLOSED.equals(cur.getRepairStatus())) {
            assertEquals(Dict.REPAIR_WAITING, cur.getRepairStatus());
            inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        }
        assertEquals(Dict.REPAIR_CLOSED, inspectionService.get(r1.getId()).getRepairStatus());
        assertEquals("已完成", stepOf(oid, "AOI").getStatus());
    }

    /**
     * 多线程复验不通过：错开到达（模拟真实网络抖动），悲观锁把写串行化，
     * 最终只开出一个第 2 轮返修任务；后续线程在锁内读到「复验不合格」走幂等分支。
     */
    @Test
    void concurrentReinspectFailOpensExactlyOneNextRound() throws Exception {
        Long oid = setupOrderWithRunningStep("WO-T7B", "回流焊");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "回流焊", 5));
        inspectionService.repair(r1.getId(), "操作员李");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                start.await();
                // 错开起跑模拟先后到达；同时到达时悲观锁 + 唯一约束也保证结果唯一
                Thread.sleep(idx * 15L);
                return inspectionService.reinspect(r1.getId(), false, "质检员周", "仍有缺陷").getId();
            }));
        }
        start.countDown();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<Inspection> rounds = repairs(oid, "回流焊");
        assertEquals(2, rounds.size(), "只有第 1 轮（已驳回）与第 2 轮（返修中）两条记录");
        assertEquals("返修中", stepOf(oid, "回流焊").getStatus());
        assertEquals(2, stepOf(oid, "回流焊").getRepairRound());
        // 返回值只能是原驳回轮（幂等重试时）或唯一的新一轮
        Long nextId = rounds.get(0).getId();
        for (Long id : ids) {
            assertTrue(id.equals(r1.getId()) || id.equals(nextId),
                    "并发复验只允许返回驳回轮或唯一的新一轮，实际出现 id=" + id);
        }
    }

    // ---------------- 不能跳过 / 不能越权 ----------------

    @Test
    void cannotSkipStepOrSelfCloseWhileRepairOpen() {
        Long oid = setupOrderWithRunningStep("WO-T8", "AOI");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "AOI", 5));

        // 返修中游标不能自行开工 / 完工 / 退回
        Long stepId = stepOf(oid, "AOI").getId();
        assertTrue(assertThrows(BizException.class, () -> smtStepService.start(stepId, "王强"))
                .getMessage().contains("返修中"));
        assertTrue(assertThrows(BizException.class, () -> smtStepService.finish(stepId))
                .getMessage().contains("复验"));
        assertThrows(BizException.class, () -> smtStepService.reset(stepId));

        // 未完成返修不能复验（只有待复验才能复验）
        assertThrows(BizException.class,
                () -> inspectionService.reinspect(r1.getId(), true, "质检员周", null));

        // 工单推进被阻塞：未完成返修不能跳过该工序
        WorkOrder o = workOrderRepository.findById(oid).orElseThrow();
        o.setStatus("待检验");
        workOrderRepository.save(o);
        BizException ex = assertThrows(BizException.class,
                () -> workOrderService.transfer(oid, "已完成"));
        assertTrue(ex.getMessage().contains("返修"));

        // 闭环后可推进
        inspectionService.repair(r1.getId(), "操作员李");
        inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        o.setDoneQty(100);
        workOrderRepository.save(o);
        assertDoesNotThrow(() -> workOrderService.transfer(oid, "已完成"));
        assertEquals("已完成", workOrderRepository.findById(oid).orElseThrow().getStatus());
    }

    @Test
    void rejectReinspectionOnRejectedRoundPointsToLatest() {
        Long oid = setupOrderWithRunningStep("WO-T9", "目检");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "目检", 3));
        inspectionService.repair(r1.getId(), "操作员李");
        Inspection r2 = inspectionService.reinspect(r1.getId(), false, "质检员周", "还有不良");
        // 已驳回轮次不允许再操作返修
        assertThrows(BizException.class, () -> inspectionService.repair(r1.getId(), "操作员李"));
        // 对已驳回轮次再发复验（网络重试）：幂等返回最新一轮返修任务，不会重复开轮
        Inspection redirected = inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        assertEquals(r2.getId(), redirected.getId());
        assertEquals(Dict.REPAIR_OPEN, redirected.getRepairStatus());
        // 新一轮可正常闭环
        inspectionService.repair(r2.getId(), "操作员李");
        assertDoesNotThrow(() -> inspectionService.reinspect(r2.getId(), true, "质检员周", null));
    }

    // ---------------- 不同工单不串单 ----------------

    @Test
    void ordersDoNotInterfere() {
        Long o1 = setupOrderWithRunningStep("WO-A", "AOI");
        Long o2 = setupOrderWithRunningStep("WO-B", "AOI");

        Inspection r1 = inspectionService.create(repairInspectionInput(o1, "AOI", 5));
        // 第二张工单并发式地独立开轮次，轮次都从 1 起，互不影响
        Inspection r2 = inspectionService.create(repairInspectionInput(o2, "AOI", 6));
        assertEquals(1, r1.getRepairRound());
        assertEquals(1, r2.getRepairRound());
        assertEquals("返修中", stepOf(o1, "AOI").getStatus());
        assertEquals("返修中", stepOf(o2, "AOI").getStatus());

        // o1 闭环不影响 o2
        inspectionService.repair(r1.getId(), "操作员李");
        inspectionService.reinspect(r1.getId(), true, "质检员周", null);
        assertEquals("已完成", stepOf(o1, "AOI").getStatus());
        assertEquals("返修中", stepOf(o2, "AOI").getStatus());
        // 阻塞口径按工单隔离
        assertFalse(Boolean.TRUE.equals(inspectionService.blocking(o1).get("blocked")));
        assertTrue(Boolean.TRUE.equals(inspectionService.blocking(o2).get("blocked")));
    }

    // ---------------- 旧数据 / 旧接口兼容 ----------------

    @Test
    void legacyInspectionMissingNewFieldsIsNormalized() {
        Long oid = newOrder("WO-LEGACY").getId();
        // 模拟旧游标：没有 repair_round 概念
        jdbc.update("INSERT INTO smt_step (id, order_id, step_name, status, operator, started_at, finished_at, repair_round) "
                + "VALUES (9001, ?, 'AOI', '返修中', NULL, NULL, NULL, 0)", oid);

        // 模拟旧库返修记录：只有 verdict + repair_done，新列全是默认值（repair_status='无'）
        jdbc.update("INSERT INTO inspection (order_id, step_name, defect_type, defect_points, sample_qty, "
                + "verdict, inspector, repair_done, checked_at, repair_round, repair_status) "
                + "VALUES (?, 'AOI', '虚焊', 5, 40, '返修', '老质检员', 0, CURRENT_TIMESTAMP, 0, '无')", oid);

        List<Inspection> list = inspectionService.list(oid, null);
        Inspection legacy = list.get(0);
        // @PostLoad 归一化：旧数据缺字段也能正确表达「返修中」
        assertEquals(Dict.REPAIR_OPEN, legacy.getRepairStatus());
        assertEquals(0, legacy.getRepairRound());
        assertFalse(legacy.getRepairDone());

        // 旧数据被当作在修任务：再登记返修必须复用它（不能新开一轮把旧任务挂死）
        Inspection again = inspectionService.create(repairInspectionInput(oid, "AOI", 7));
        assertEquals(legacy.getId(), again.getId());

        // 旧数据闭环路径正常（repairDone 旧字段同步翻转）
        inspectionService.repair(legacy.getId(), "操作员李");
        Inspection closed = inspectionService.reinspect(legacy.getId(), true, "质检员周", null);
        assertTrue(closed.getRepairDone());
        assertEquals("已完成", stepOf(oid, "AOI").getStatus());
    }

    @Test
    void legacyClosedRepairIsTreatedAsClosed() {
        Long oid = newOrder("WO-LEGACY2").getId();
        jdbc.update("INSERT INTO inspection (order_id, step_name, defect_type, defect_points, sample_qty, "
                + "verdict, inspector, repair_done, checked_at, repair_round, repair_status) "
                + "VALUES (?, 'AOI', '偏移', 3, 40, '返修', '老质检员', 1, CURRENT_TIMESTAMP, 0, '无')", oid);

        Inspection legacy = inspectionService.list(oid, null).get(0);
        assertEquals(Dict.REPAIR_CLOSED, legacy.getRepairStatus());
        assertTrue(legacy.getRepairDone());
        // 已闭环历史返修不占放行阻塞
        assertFalse(Boolean.TRUE.equals(inspectionService.blocking(oid).get("blocked")));
        // 同一工序再次判返修时按第 1 轮（旧库没有轮次记录）开新任务
        SmtStep s = new SmtStep();
        s.setOrder(workOrderRepository.findById(oid).orElseThrow());
        s.setStepName("AOI");
        s.setStatus("进行中");
        s.setRepairRound(0);
        smtStepRepository.save(s);
        Inspection fresh = inspectionService.create(repairInspectionInput(oid, "AOI", 4));
        assertEquals(1, fresh.getRepairRound());
    }

    @Test
    void plainVerdictsKeepLegacyRepairDoneSemantics() {
        Long oid = setupOrderWithRunningStep("WO-OK", "AOI");
        Inspection pass = inspectionService.create(repairInspectionInput(oid, "AOI", 0));
        assertEquals("合格", pass.getVerdict());
        assertTrue(pass.getRepairDone(), "旧接口口径：合格记录 repairDone=true");
        assertEquals(0, pass.getRepairRound());
        assertEquals(Dict.REPAIR_NA, pass.getRepairStatus());

        jdbc.update("DELETE FROM inspection");
        Inspection scrap = inspectionService.create(repairInspectionInput(oid, "AOI", 20));
        assertEquals("判废", scrap.getVerdict());
        assertTrue(scrap.getRepairDone(), "判废记录不走返修闭环，旧字段仍为 true");
    }

    // ---------------- 数据库唯一约束兜底 ----------------

    @Test
    void databaseUniqueConstraintRejectsDuplicateRepairRound() {
        Long oid = setupOrderWithRunningStep("WO-UK", "AOI");
        Inspection r1 = inspectionService.create(repairInspectionInput(oid, "AOI", 5));
        // 绕过 Service 直接插同单同工序同轮次：必须被唯一索引拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO inspection (order_id, step_name, defect_type, defect_points, sample_qty, "
                        + "verdict, inspector, repair_done, checked_at, repair_round, repair_status) "
                        + "VALUES (?, 'AOI', '虚焊', 5, 40, '返修', 'x', 0, CURRENT_TIMESTAMP, ?, '返修中')",
                oid, r1.getRepairRound()));
        // 非返修记录不受该约束影响
        assertDoesNotThrow(() -> jdbc.update(
                "INSERT INTO inspection (order_id, step_name, defect_type, defect_points, sample_qty, "
                        + "verdict, inspector, repair_done, checked_at, repair_round, repair_status) "
                        + "VALUES (?, 'AOI', '反向', 0, 40, '合格', 'x', 1, CURRENT_TIMESTAMP, 0, '无')",
                oid));
        // 工序游标唯一：同单同工序不能两条
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO smt_step (order_id, step_name, status, repair_round) VALUES (?, 'AOI', '待执行', 0)",
                oid));
    }

    @Test
    void scrappedVerdictBlocksOrderAdvance() {
        Long oid = setupOrderWithRunningStep("WO-SCRAP", "AOI");
        inspectionService.create(repairInspectionInput(oid, "AOI", 20)); // 判废
        WorkOrder o = workOrderRepository.findById(oid).orElseThrow();
        o.setStatus("待检验");
        workOrderRepository.save(o);
        assertThrows(BizException.class, () -> workOrderService.transfer(oid, "已完成"));
    }
}
