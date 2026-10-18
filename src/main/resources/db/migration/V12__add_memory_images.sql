CREATE TABLE memory_images (
    id CHAR(32) PRIMARY KEY,
    memory_id BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    byte_size INT NOT NULL,
    data MEDIUMBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_memory_image_hash (memory_id, sha256),
    CONSTRAINT fk_memory_image FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
