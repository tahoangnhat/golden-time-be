ALTER TABLE articles
    ADD COLUMN IF NOT EXISTS cover_alt VARCHAR(512),
    ADD COLUMN IF NOT EXISTS seo_title VARCHAR(255),
    ADD COLUMN IF NOT EXISTS seo_description TEXT,
    ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_articles_published_featured
    ON articles (published, featured DESC, published_at DESC, id DESC);
