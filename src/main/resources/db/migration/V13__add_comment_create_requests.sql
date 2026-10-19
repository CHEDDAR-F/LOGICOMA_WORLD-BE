-- Additive receipt ledger. Retain tombstones after comment deletion; never expire keys silently.
CREATE TABLE comment_create_requests (
    agent_id BIGINT NOT NULL,
    request_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comment_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, request_key_hash),
    CONSTRAINT fk_comment_request_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_request_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE SET NULL
) ENGINE=InnoDB;
