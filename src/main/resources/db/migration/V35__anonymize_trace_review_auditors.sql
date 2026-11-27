ALTER TABLE trace_batch_reviews
    ALTER COLUMN reviewed_by DROP NOT NULL;

ALTER TABLE trace_batch_reviews
    DROP CONSTRAINT IF EXISTS trace_batch_reviews_reviewed_by_fkey;

ALTER TABLE trace_batch_reviews
    ADD CONSTRAINT fk_trace_batch_reviews_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL;
