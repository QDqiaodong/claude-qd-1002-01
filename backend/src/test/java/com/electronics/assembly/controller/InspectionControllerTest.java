package com.electronics.assembly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 质检 / 返修接口的 HTTP 层测试：旧接口响应字段（verdict / repairDone / orderNo / defectRate）
 * 保持不变，新增 repairRound / repairStatus 等字段并存；复验接口校验入参。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM inspection");
        jdbc.update("DELETE FROM smt_step");
        jdbc.update("DELETE FROM work_order");
        jdbc.update("INSERT INTO work_order (id, order_no, product_model, plan_qty, done_qty, priority, status, due_date, created_at) "
                + "VALUES (71, 'WO-WEB', 'MODEL-T', 100, 0, '高', '贴片中', DATEADD('DAY', 5, CURRENT_DATE), CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO smt_step (order_id, step_name, status, repair_round) VALUES (71, 'AOI', '进行中', 0)");
    }

    @Test
    void createRepairKeepsLegacyResponseFieldsAndExposesNewOnes() throws Exception {
        String body = "{\"order\":{\"id\":71},\"stepName\":\"AOI\",\"defectType\":\"虚焊\","
                + "\"defectPoints\":4,\"sampleQty\":40,\"inspector\":\"周涛\"}";
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                // 旧字段原样保留
                .andExpect(jsonPath("$.data.verdict").value("返修"))
                .andExpect(jsonPath("$.data.repairDone").value(false))
                .andExpect(jsonPath("$.data.orderNo").value("WO-WEB"))
                .andExpect(jsonPath("$.data.defectRate").value(100.0))
                // 新字段
                .andExpect(jsonPath("$.data.repairRound").value(1))
                .andExpect(jsonPath("$.data.repairStatus").value("返修中"));

        // 重复提交（网络重试）：同一条记录，幂等
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repairRound").value(1));

        Long id = jdbc.queryForObject(
                "SELECT id FROM inspection WHERE order_id=71 AND step_name='AOI'", Long.class);
        mockMvc.perform(get("/api/inspections/{id}", id))
                .andExpect(jsonPath("$.data.repairStatus").value("返修中"));
        mockMvc.perform(get("/api/inspections/blocking/71"))
                .andExpect(jsonPath("$.data.blocked").value(true))
                .andExpect(jsonPath("$.data.openRepairs").value(1));
    }

    @Test
    void repairLegacyPathStillWorksAndReinspectDrivesClosing() throws Exception {
        String body = "{\"order\":{\"id\":71},\"stepName\":\"AOI\",\"defectType\":\"偏移\","
                + "\"defectPoints\":3,\"sampleQty\":40,\"inspector\":\"周涛\"}";
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        Long id = jdbc.queryForObject(
                "SELECT id FROM inspection WHERE order_id=71 AND step_name='AOI'", Long.class);

        // 旧路径 /repair 无 body 仍可调（operator 走 query，兼容旧前端调用方式）
        mockMvc.perform(post("/api/inspections/{id}/repair", id).param("operator", "李敏"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repairStatus").value("待复验"));

        // 复验缺 passed：400 + 中文提示
        mockMvc.perform(post("/api/inspections/{id}/reinspect", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        // 复验不通过缺原因：400
        mockMvc.perform(post("/api/inspections/{id}/reinspect", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passed\":false,\"inspector\":\"周涛\"}"))
                .andExpect(status().isBadRequest());

        // 复验通过：闭环，工序完成
        mockMvc.perform(post("/api/inspections/{id}/reinspect", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passed\":true,\"inspector\":\"周涛\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repairStatus").value("已闭环"))
                .andExpect(jsonPath("$.data.repairDone").value(true));

        mockMvc.perform(get("/api/smt-steps").param("orderId", "71"))
                .andExpect(jsonPath("$.data[?(@.stepName=='AOI')].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("已完成"))));
    }
}
