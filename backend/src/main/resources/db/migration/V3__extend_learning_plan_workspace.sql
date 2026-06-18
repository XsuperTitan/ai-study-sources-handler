ALTER TABLE learning_plan_step
    ADD COLUMN scheduled_date DATE NULL AFTER estimated_minutes,
    ADD COLUMN actual_minutes INT NOT NULL DEFAULT 0 AFTER scheduled_date,
    ADD COLUMN stage_label VARCHAR(80) NOT NULL DEFAULT '' AFTER actual_minutes,
    ADD COLUMN reflection TEXT NULL AFTER stage_label;

CREATE TABLE learning_plan_study_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    step_id CHAR(36) NOT NULL,
    minutes INT NOT NULL,
    note TEXT NULL,
    studied_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_study_session_id UNIQUE (session_id),
    INDEX idx_learning_plan_study_session_step (step_id, studied_at),
    CONSTRAINT fk_learning_plan_study_session_plan FOREIGN KEY (plan_id)
        REFERENCES learning_plan(plan_id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_plan_study_session_step FOREIGN KEY (step_id)
        REFERENCES learning_plan_step(step_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    input_snapshot JSON NOT NULL,
    output_snapshot JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_version_id UNIQUE (version_id),
    INDEX idx_learning_plan_version_plan (plan_id, version),
    CONSTRAINT fk_learning_plan_version_plan FOREIGN KEY (plan_id)
        REFERENCES learning_plan(plan_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan_replan_proposal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    proposal_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    steps_snapshot JSON NOT NULL,
    input_snapshot JSON NOT NULL,
    output_snapshot JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_replan_proposal_id UNIQUE (proposal_id),
    INDEX idx_learning_plan_replan_proposal_plan (plan_id, created_at),
    CONSTRAINT fk_learning_plan_replan_proposal_plan FOREIGN KEY (plan_id)
        REFERENCES learning_plan(plan_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
