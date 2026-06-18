CREATE TABLE learning_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id CHAR(36) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    title VARCHAR(255) NOT NULL DEFAULT '',
    overview TEXT NOT NULL,
    estimated_minutes INT NOT NULL DEFAULT 0,
    generated_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_id UNIQUE (plan_id),
    CONSTRAINT uk_learning_plan_owner_status UNIQUE (owner_id, status),
    INDEX idx_learning_plan_owner_updated (owner_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan_package (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id CHAR(36) NOT NULL,
    package_id CHAR(36) NOT NULL,
    title_snapshot VARCHAR(255) NOT NULL,
    keywords_snapshot JSON NOT NULL,
    package_status_snapshot VARCHAR(32) NOT NULL,
    position INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_package UNIQUE (plan_id, package_id),
    INDEX idx_learning_plan_package_position (plan_id, position),
    CONSTRAINT fk_learning_plan_package_plan FOREIGN KEY (plan_id)
        REFERENCES learning_plan(plan_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    step_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    package_ids_snapshot JSON NOT NULL,
    estimated_minutes INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'TODO',
    position INT NOT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_plan_step_id UNIQUE (step_id),
    INDEX idx_learning_plan_step_position (plan_id, position),
    CONSTRAINT fk_learning_plan_step_plan FOREIGN KEY (plan_id)
        REFERENCES learning_plan(plan_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
