package com.electronics.assembly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.ReinspectRequest;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.service.InspectionService;

/** 质检与返修接口 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public ApiResponse<List<Inspection>> list(@RequestParam(required = false) Long orderId,
                                              @RequestParam(required = false) String verdict) {
        return ApiResponse.success(inspectionService.list(orderId, verdict));
    }

    @GetMapping("/{id}")
    public ApiResponse<Inspection> get(@PathVariable Long id) {
        return ApiResponse.success(inspectionService.get(id));
    }

    /** 登记质检，结论按不良点数自动判定；判返修时幂等创建 / 复用返修任务 */
    @PostMapping
    public ApiResponse<Inspection> create(@RequestBody Inspection input) {
        Inspection saved = inspectionService.create(input);
        String msg = "返修".equals(saved.getVerdict())
                ? "已登记质检，结论「返修」第 " + saved.getRepairRound()
                        + " 轮，工序已置返修中"
                : "质检已登记，结论「" + saved.getVerdict() + "」";
        return ApiResponse.success(msg, saved);
    }

    /**
     * 操作员完成返修：返修中 → 待复验（保留旧路径，入参兼容旧调用：
     * 无 body / query 时按「未指派」登记）。最终闭环只能走 /reinspect。
     */
    @PostMapping("/{id}/repair")
    public ApiResponse<Inspection> repair(@PathVariable Long id,
                                          @RequestParam(required = false) String operator) {
        return ApiResponse.success("返修已完成，等待质检复验", inspectionService.repair(id, operator));
    }

    /** 质检复验：返修闭环唯一收口。返回复验通过的旧记录，或不通过时新开启的返修任务 */
    @PostMapping("/{id}/reinspect")
    public ApiResponse<Inspection> reinspect(@PathVariable Long id,
                                             @RequestBody(required = false) ReinspectRequest req) {
        if (req == null || req.getPassed() == null) {
            throw new BizException("必须指定复验结果 passed（true / false）");
        }
        Inspection out = inspectionService.reinspect(id, req.getPassed(),
                req.getInspector(), req.getNote());
        boolean openedNext = "返修".equals(out.getVerdict())
                && Dict.REPAIR_OPEN.equals(out.getRepairStatus())
                && !out.getId().equals(id);
        String msg = Boolean.TRUE.equals(req.getPassed())
                ? "复验通过，返修闭环，工序已完成"
                : openedNext
                        ? "复验不通过，已自动开启第 " + out.getRepairRound() + " 轮返修"
                        : "复验不通过（重试返回在修的第 " + out.getRepairRound() + " 轮返修）";
        return ApiResponse.success(msg, out);
    }

    /** 未闭环的返修单 */
    @GetMapping("/open-repairs")
    public ApiResponse<List<Inspection>> openRepairs() {
        return ApiResponse.success(inspectionService.openRepairs());
    }

    /** 某工单是否放行阻塞 */
    @GetMapping("/blocking/{orderId}")
    public ApiResponse<Map<String, Object>> blocking(@PathVariable Long orderId) {
        return ApiResponse.success(inspectionService.blocking(orderId));
    }
}
