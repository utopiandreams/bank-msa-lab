-- 외부 쌍 (타행 송금 ↔ 외부 은행 정산 파일, ADR-0006 결정 7·19). 회차를 쌍별로 나눈다
ALTER TABLE reconciliation.recon_run ADD COLUMN pair varchar(30) NOT NULL DEFAULT 'TRANSFER_CORE';
ALTER TABLE reconciliation.recon_run ALTER COLUMN pair DROP DEFAULT;
ALTER TABLE reconciliation.recon_run ALTER COLUMN core_extract_id DROP NOT NULL;
ALTER TABLE reconciliation.recon_run ADD COLUMN external_extract_id uuid;
-- 쌍마다 상대 추출이 정확히 하나
ALTER TABLE reconciliation.recon_run ADD CONSTRAINT ck_recon_run_pair CHECK (
    (pair = 'TRANSFER_CORE' AND core_extract_id IS NOT NULL AND external_extract_id IS NULL) OR
    (pair = 'TRANSFER_EXTERNAL' AND external_extract_id IS NOT NULL AND core_extract_id IS NULL)
);
DROP INDEX reconciliation.ix_recon_run_date;
CREATE INDEX ix_recon_run_pair_date ON reconciliation.recon_run (pair, business_date, status);
