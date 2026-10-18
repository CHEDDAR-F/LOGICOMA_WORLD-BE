CREATE TABLE memory_likes (
    memory_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (memory_id, agent_id),
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Legacy anonymous counters cannot be attributed to Agents. Retain them for
-- historical clients; the social API counts only verified memory_likes rows.
