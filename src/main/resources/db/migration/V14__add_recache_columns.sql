ALTER TABLE db_item
    ADD COLUMN IF NOT EXISTS recache_attempted_at BIGINT;

-- Add nzb_bytes column (OID type for Hibernate @Lob + PostgreSQL)
ALTER TABLE debrid_cached_usenet_release_content
    ADD COLUMN IF NOT EXISTS nzb_bytes OID;

-- If column was created with wrong type by JPA auto-DDL, fix it
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'debrid_cached_usenet_release_content'
        AND column_name = 'nzb_bytes'
        AND data_type != 'oid'
    ) THEN
        ALTER TABLE debrid_cached_usenet_release_content
        ALTER COLUMN nzb_bytes TYPE OID USING nzb_bytes::text::oid;
    END IF;
END $$;
