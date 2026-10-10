-- agentswitchyard V1 基线表
-- 可移植 DDL：H2 / MySQL / PostgreSQL 直接执行；达梦用同一 DDL（生产接入时经达梦 Flyway 分支执行）。
-- ★ 幂等键与步骤记录同表：step_record 的 (flow_instance_id, step_id, idem_key) 唯一索引（V2 落地）
--   是「插入即判定」幂等语义的物理载体 —— 不存在先查幂等表再执行再写步骤表的跨表竞态。

CREATE TABLE flow_instance (
    flow_instance_id VARCHAR(64)  NOT NULL,
    flow_id          VARCHAR(128) NOT NULL,
    flow_version     INT          NOT NULL,
    tenant_id        VARCHAR(64),
    trace_id         VARCHAR(64)  NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    input_json       TEXT,
    started_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_flow_instance PRIMARY KEY (flow_instance_id)
);

CREATE TABLE step_record (
    flow_instance_id VARCHAR(64)   NOT NULL,
    step_id          VARCHAR(128)  NOT NULL,
    seq              INT           NOT NULL,
    kind             VARCHAR(16)   NOT NULL,
    idem_key         VARCHAR(256)  NOT NULL,
    state            VARCHAR(32)   NOT NULL,
    span_id          VARCHAR(64)   NOT NULL,
    input_json       TEXT,
    output_json      TEXT,
    usage_json       TEXT,
    cost_cny         DECIMAL(19,8) NOT NULL,
    attempts         INT           NOT NULL,
    started_at       TIMESTAMP     NOT NULL,
    ended_at         TIMESTAMP,
    error_message    TEXT,
    approval_id      VARCHAR(64),
    CONSTRAINT pk_step_record PRIMARY KEY (flow_instance_id, step_id),
    CONSTRAINT fk_step_instance FOREIGN KEY (flow_instance_id)
        REFERENCES flow_instance (flow_instance_id)
);

CREATE TABLE approval_record (
    approval_id      VARCHAR(64)  NOT NULL,
    flow_instance_id VARCHAR(64)  NOT NULL,
    step_id          VARCHAR(128) NOT NULL,
    approver_role    VARCHAR(64)  NOT NULL,
    state            VARCHAR(16)  NOT NULL,
    approver         VARCHAR(128),
    note             TEXT,
    reason           VARCHAR(512),
    degraded         BOOLEAN      NOT NULL,
    requested_at     TIMESTAMP    NOT NULL,
    decided_at       TIMESTAMP,
    due_at           TIMESTAMP,
    timeout_policy   VARCHAR(32)  NOT NULL,
    CONSTRAINT pk_approval_record PRIMARY KEY (approval_id)
);

CREATE TABLE replay_blob (
    flow_instance_id VARCHAR(64) NOT NULL,
    span_id          VARCHAR(64) NOT NULL,
    seq              INT         NOT NULL,
    model_id         VARCHAR(128),
    request_digest   VARCHAR(80) NOT NULL,
    response_json    TEXT        NOT NULL,
    recorded_at      TIMESTAMP   NOT NULL,
    CONSTRAINT pk_replay_blob PRIMARY KEY (flow_instance_id, span_id, seq)
);
