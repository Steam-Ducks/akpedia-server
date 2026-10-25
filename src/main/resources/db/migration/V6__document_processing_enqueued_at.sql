-- When an approved document was actually handed to the indexing worker (added to the
-- documents:processing Redis Stream). NULL while it has not been: the approval commits first
-- and the stream is written after, so a crash or a Redis outage in between leaves the document
-- approved but never enqueued. The server finds those by this column and sends them again,
-- which makes the documents row itself the outbox for the stream.

ALTER TABLE documents
    ADD COLUMN processing_enqueued_at TIMESTAMPTZ;

-- Only the documents still waiting to be enqueued are ever looked up, and there are few of them
-- at any time: a partial index keeps that lookup cheap without indexing every document.
CREATE INDEX ix_documents_pending_enqueue
    ON documents (approved_at)
    WHERE status = 'APPROVED'
      AND processing_status = 'PROCESSING'
      AND processing_enqueued_at IS NULL;
