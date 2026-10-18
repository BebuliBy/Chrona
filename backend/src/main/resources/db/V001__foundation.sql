CREATE TABLE tx_guard (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL);
INSERT INTO tx_guard VALUES (1, 0);
CREATE TABLE accounts (
 id VARCHAR(36) PRIMARY KEY, telegram_id BIGINT NOT NULL UNIQUE,
 display_name VARCHAR(120) NOT NULL, discoverable BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE login_challenges (
 id VARCHAR(36) PRIMARY KEY, device_id VARCHAR(36) NOT NULL, device_label VARCHAR(80) NOT NULL,
 challenge VARCHAR(64) NOT NULL, expires_ms BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 telegram_id BIGINT, account_id VARCHAR(36), chat_id BIGINT
);
CREATE TABLE device_sessions (
 id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
 device_id VARCHAR(36) NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
 access_hash VARCHAR(64) NOT NULL UNIQUE, access_expires_ms BIGINT NOT NULL,
 refresh_hash VARCHAR(64) NOT NULL UNIQUE, refresh_expires_ms BIGINT NOT NULL
);
CREATE TABLE spent_refresh (
 token_hash VARCHAR(64) PRIMARY KEY, session_id VARCHAR(36) NOT NULL REFERENCES device_sessions(id)
);
CREATE TABLE entities (
 owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id), entity_type VARCHAR(32) NOT NULL,
 id VARCHAR(36) NOT NULL, revision VARCHAR(40) NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(owner_id, entity_type, id)
);
CREATE TABLE operation_dedup (
 owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id), operation_id VARCHAR(36) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, result TEXT NOT NULL, PRIMARY KEY(owner_id, operation_id)
);
CREATE TABLE request_dedup (
 owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id), key_id VARCHAR(36) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, result TEXT NOT NULL, PRIMARY KEY(owner_id, key_id)
);
CREATE TABLE change_log (
 sequence_no BIGINT PRIMARY KEY, owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
 entity_type VARCHAR(32) NOT NULL, entity_id VARCHAR(36) NOT NULL, revision VARCHAR(40) NOT NULL,
 payload TEXT NOT NULL
);
CREATE INDEX change_owner_seq ON change_log(owner_id, sequence_no);
CREATE TABLE sync_cursors (token_hash VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id), sequence_no BIGINT NOT NULL);
CREATE TABLE friend_requests (
 id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
 target_id VARCHAR(36) NOT NULL REFERENCES accounts(id), state VARCHAR(16) NOT NULL
);
CREATE TABLE friendships (
 account_a VARCHAR(36) NOT NULL REFERENCES accounts(id), account_b VARCHAR(36) NOT NULL REFERENCES accounts(id),
 PRIMARY KEY(account_a, account_b)
);
CREATE TABLE grants (
 owner_id VARCHAR(36) NOT NULL REFERENCES accounts(id), friend_id VARCHAR(36) NOT NULL REFERENCES accounts(id),
 revision VARCHAR(40) NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner_id, friend_id)
);
CREATE TABLE bot_state (
 id INTEGER PRIMARY KEY, next_offset BIGINT NOT NULL, lease_holder VARCHAR(36), lease_until_ms BIGINT NOT NULL,
 progress_ms BIGINT NOT NULL, success_ms BIGINT NOT NULL
);
INSERT INTO bot_state VALUES (1, 0, NULL, 0, 0, 0);
CREATE TABLE bot_inbox (
 update_id BIGINT PRIMARY KEY, payload TEXT NOT NULL, processed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE bot_outbox (
 id VARCHAR(120) PRIMARY KEY, chat_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL,
 payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_ms BIGINT NOT NULL,
 delivered BOOLEAN NOT NULL DEFAULT FALSE, discarded BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE rate_limits (bucket VARCHAR(160) PRIMARY KEY, window_ms BIGINT NOT NULL, hits INTEGER NOT NULL);
