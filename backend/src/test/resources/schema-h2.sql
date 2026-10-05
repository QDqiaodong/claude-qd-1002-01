-- H2（MODE=MySQL）测试库结构：与 mysql/schema.sql 的列 / 约束口径保持一致。
-- 不写 ENGINE / CHARSET（H2 不接受），生成列改用 CASE WHEN（MySQL 8 与 H2 都支持）。
-- 多个 Spring 测试上下文复用同一内存库时（DB_CLOSE_DELAY=-1），脚本可重复执行。

DROP TABLE IF EXISTS line_change_event;
DROP TABLE IF EXISTS line_change_batch;
DROP TABLE IF EXISTS order_line_config;
DROP TABLE IF EXISTS inspection;
DROP TABLE IF EXISTS smt_step;
DROP TABLE IF EXISTS bom_item;
DROP TABLE IF EXISTS component;
DROP TABLE IF EXISTS work_order;

CREATE TABLE work_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    product_model VARCHAR(48) NOT NULL,
    plan_qty      INT         NOT NULL,
    done_qty      INT         NOT NULL DEFAULT 0,
    priority      VARCHAR(8)  NOT NULL,
    status        VARCHAR(16) NOT NULL,
    due_date      DATE,
    created_at    DATETIME
);

CREATE TABLE component (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    part_no      VARCHAR(32) NOT NULL UNIQUE,
    part_name    VARCHAR(64) NOT NULL,
    package_type VARCHAR(16) NOT NULL,
    stock_qty    INT         NOT NULL DEFAULT 0,
    feed_slot    VARCHAR(16),
    status       VARCHAR(16) NOT NULL
);

CREATE TABLE bom_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_model VARCHAR(48) NOT NULL,
    part_no       VARCHAR(32) NOT NULL,
    unit_qty      INT         NOT NULL,
    CONSTRAINT uk_bom UNIQUE (product_model, part_no)
);

CREATE TABLE smt_step (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    step_name    VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    operator     VARCHAR(32),
    started_at   DATETIME,
    finished_at  DATETIME,
    repair_round INT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_step_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT uk_step_order_step UNIQUE (order_id, step_name)
);

CREATE TABLE inspection (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT      NOT NULL,
    step_name      VARCHAR(16) NOT NULL,
    defect_type    VARCHAR(16) NOT NULL,
    defect_points  INT         NOT NULL DEFAULT 0,
    sample_qty     INT         NOT NULL DEFAULT 0,
    verdict        VARCHAR(16) NOT NULL,
    inspector      VARCHAR(32),
    repair_done    TINYINT     NOT NULL DEFAULT 0,
    checked_at     DATETIME,
    repair_round   INT         NOT NULL DEFAULT 0,
    repair_status  VARCHAR(16) NOT NULL DEFAULT '无',
    repaired_by    VARCHAR(32),
    repaired_at    DATETIME,
    reinspected_by VARCHAR(32),
    reinspected_at DATETIME,
    reinspect_note VARCHAR(255),
    repair_uk      VARCHAR(64) GENERATED ALWAYS AS (
                       CASE WHEN verdict = '返修' AND repair_round > 0
                            THEN CONCAT(order_id, '-', step_name, '-', repair_round)
                            ELSE NULL END
                   ),
    CONSTRAINT fk_insp_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT uk_insp_repair_round UNIQUE (repair_uk)
);

CREATE TABLE order_line_config (
    order_id      BIGINT PRIMARY KEY,
    equipment_no  VARCHAR(32)  NOT NULL,
    feeder_config VARCHAR(255) NOT NULL,
    key_params    VARCHAR(255) NOT NULL,
    config_hash   VARCHAR(64)  NOT NULL,
    updated_by    VARCHAR(32),
    updated_at    DATETIME,
    CONSTRAINT fk_olc_order FOREIGN KEY (order_id) REFERENCES work_order (id)
);

CREATE TABLE line_change_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT       NOT NULL,
    version_no     INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    equipment_no   VARCHAR(32)  NOT NULL,
    feeder_config  VARCHAR(255) NOT NULL,
    key_params     VARCHAR(255) NOT NULL,
    config_hash    VARCHAR(64)  NOT NULL,
    change_reason  VARCHAR(255),
    created_by     VARCHAR(32)  NOT NULL,
    created_at     DATETIME,
    first_result   VARCHAR(16),
    first_note     VARCHAR(255),
    submitted_by   VARCHAR(32),
    submitted_at   DATETIME,
    confirmed_by   VARCHAR(32),
    confirmed_at   DATETIME,
    reject_reason  VARCHAR(255),
    active_order_id BIGINT GENERATED ALWAYS AS (
                        CASE WHEN status IN ('待首件','待确认','已放行') THEN order_id ELSE NULL END
                    ),
    CONSTRAINT fk_lcb_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT uk_lcb_version UNIQUE (order_id, version_no),
    CONSTRAINT uk_lcb_active UNIQUE (active_order_id)
);

CREATE TABLE line_change_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL,
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_lce_batch FOREIGN KEY (batch_id) REFERENCES line_change_batch (id)
);
