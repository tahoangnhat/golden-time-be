ALTER TABLE trace_batches
    ADD COLUMN review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (review_status IN ('PENDING_REVIEW', 'VERIFIED', 'NEEDS_CORRECTION')),
    ADD COLUMN review_version INT NOT NULL DEFAULT 0 CHECK (review_version >= 0),
    ADD COLUMN review_note TEXT,
    ADD COLUMN reviewed_by BIGINT REFERENCES users (id),
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN submitted_at TIMESTAMPTZ;

UPDATE trace_batches SET submitted_at = created_at;

CREATE INDEX idx_trace_batches_review_status ON trace_batches (review_status, submitted_at DESC);

CREATE TABLE trace_batch_evidence (
    id              BIGSERIAL PRIMARY KEY,
    batch_code      VARCHAR(64) NOT NULL REFERENCES trace_batches (batch_code) ON DELETE CASCADE,
    review_version  INT NOT NULL,
    image_url       VARCHAR(1024) NOT NULL,
    display_order   INT NOT NULL DEFAULT 0,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (batch_code, review_version, image_url)
);

CREATE INDEX idx_trace_batch_evidence_version
    ON trace_batch_evidence (batch_code, review_version, display_order);

CREATE TABLE trace_batch_reviews (
    id              BIGSERIAL PRIMARY KEY,
    batch_code      VARCHAR(64) NOT NULL REFERENCES trace_batches (batch_code) ON DELETE CASCADE,
    review_version  INT NOT NULL,
    decision        VARCHAR(24) NOT NULL CHECK (decision IN ('VERIFIED', 'NEEDS_CORRECTION')),
    note            TEXT,
    reviewed_by     BIGINT NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trace_batch_reviews_batch
    ON trace_batch_reviews (batch_code, review_version, created_at DESC);
