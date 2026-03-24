CREATE TABLE click_events (
    id BIGSERIAL PRIMARY KEY,
    short_url VARCHAR(8) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    clicked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_url FOREIGN KEY (short_url) REFERENCES urls (short_url) ON DELETE CASCADE
);
CREATE INDEX idx_click_events_short_url ON click_events (short_url);
