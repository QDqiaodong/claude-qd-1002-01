-- ---------------------------------------------------------------------
-- V2 · 修复「质检不合格 → 返修」闭环：返修轮次 / 返修状态机 / 复验字段 /
--       工序返修中游标 + 幂等唯一约束。
--
-- 兼容口径：
--   * 所有新列都带默认值或允许 NULL，历史质检数据无需人工补字段即可运行；
--   * 历史「返修」记录按 repair_done 回填 repair_status（1 → 已闭环，0 → 返修中）；
--   * 历史未闭环返修对应的工序游标回填为「返修中」、repair_round 回填为 1；
--   * MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 方言），
--     这里用 information_schema + 存储过程做幂等守卫，脚本可重复执行。
--
-- 用法：mysql electronics_assembly < V2__repair_loop.sql
-- ---------------------------------------------------------------------

USE electronics_assembly;

DELIMITER //

-- 若列不存在则追加列定义
DROP PROCEDURE IF EXISTS v2_add_column //
CREATE PROCEDURE v2_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl VARCHAR(512))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END //

-- 若索引不存在则创建
DROP PROCEDURE IF EXISTS v2_add_index //
CREATE PROCEDURE v2_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl VARCHAR(512))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END //

DELIMITER ;

-- ---------------- smt_step：返修轮次 + 工序唯一游标 ----------------
CALL v2_add_column('smt_step', 'repair_round',
    "repair_round INT NOT NULL DEFAULT 0 COMMENT '当前返修轮次，0 = 未发生返修'");

-- 历史脏数据兜底：万一旧库存在同单同工序重复游标行，先去重再建唯一索引（保留最早一条）
DELETE s1 FROM smt_step s1
JOIN smt_step s2
  ON s1.order_id = s2.order_id AND s1.step_name = s2.step_name AND s1.id > s2.id;

CALL v2_add_index('smt_step', 'uk_step_order_step',
    'UNIQUE KEY uk_step_order_step (order_id, step_name)');

-- ---------------- inspection：返修轮次 / 状态机 / 复验字段 ----------------
CALL v2_add_column('inspection', 'repair_round',
    "repair_round INT NOT NULL DEFAULT 0 COMMENT '返修轮次，返修记录从 1 递增；合格/判废为 0'");
CALL v2_add_column('inspection', 'repair_status',
    "repair_status VARCHAR(16) NOT NULL DEFAULT '无' COMMENT '无/返修中/待复验/复验不合格/已闭环'");
CALL v2_add_column('inspection', 'repaired_by',
    'repaired_by VARCHAR(32) COMMENT ''完成返修的操作员''');
CALL v2_add_column('inspection', 'repaired_at',
    'repaired_at DATETIME COMMENT ''返修完成时间''');
CALL v2_add_column('inspection', 'reinspected_by',
    'reinspected_by VARCHAR(32) COMMENT ''复验质检员''');
CALL v2_add_column('inspection', 'reinspected_at',
    'reinspected_at DATETIME COMMENT ''复验时间''');
CALL v2_add_column('inspection', 'reinspect_note',
    'reinspect_note VARCHAR(255) COMMENT ''复验备注（不通过必填）''');

-- 历史返修记录补轮次：按工单+工序分组，第 n 条返修记录就是第 n 轮
UPDATE inspection i
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY order_id, step_name ORDER BY checked_at, id) AS rn
    FROM inspection
    WHERE verdict = '返修'
) r ON r.id = i.id
SET i.repair_round = r.rn
WHERE i.repair_round = 0;

-- 历史返修记录补状态：已闭环 / 返修中（仅回填仍是占位值 '无' 的行）
UPDATE inspection
SET repair_status = CASE WHEN repair_done = 1 THEN '已闭环' ELSE '返修中' END
WHERE verdict = '返修' AND repair_status = '无';

-- 历史未闭环返修：对应工序游标回填「返修中」并同步轮次
UPDATE smt_step s
JOIN inspection i
  ON i.order_id = s.order_id AND i.step_name = s.step_name
SET s.status = '返修中',
    s.repair_round = GREATEST(s.repair_round, i.repair_round)
WHERE i.verdict = '返修' AND i.repair_status = '返修中';

-- 幂等占位生成列 + 唯一索引：同单同工序同轮次只允许一条返修记录
CALL v2_add_column('inspection', 'repair_uk',
    "repair_uk VARCHAR(64) GENERATED ALWAYS AS ("
    "  IF(verdict = '返修' AND repair_round > 0, CONCAT(order_id, '-', step_name, '-', repair_round), NULL)"
    ") STORED COMMENT '返修记录幂等占位'");

-- 若历史数据已存在同单同工序同轮次的重复返修行，保留最早一条
DELETE i1 FROM inspection i1
JOIN inspection i2
  ON i1.repair_uk = i2.repair_uk AND i1.id > i2.id
WHERE i1.repair_uk IS NOT NULL;

CALL v2_add_index('inspection', 'uk_insp_repair_round',
    'UNIQUE KEY uk_insp_repair_round (repair_uk)');

DROP PROCEDURE IF EXISTS v2_add_column;
DROP PROCEDURE IF EXISTS v2_add_index;
