-- bank-mock(외부 결제망 흉내)의 처리 기록 (2026-11-26). 이 기록이 곧 조회 API의 답과 일자별 정산 파일의 원본이다.
-- 재시작해도 남아야 한다: 메모리였을 때 재시작 한 번에 그날 처리분을 잃어 대사에 MISSING_EXTERNAL 12건이 생겼다
CREATE SCHEMA IF NOT EXISTS bankmock;

CREATE TABLE bankmock.interbank_transfer (
    idempotency_key   VARCHAR(100) PRIMARY KEY,          -- 보내는 쪽이 준 키 (우리 송금 ID). 같은 키는 한 번만 판정 = PK가 멱등을 지킨다
    to_bank_code      VARCHAR(10)  NOT NULL,
    to_account_number VARCHAR(50)  NOT NULL,
    amount            BIGINT       NOT NULL CHECK (amount > 0),
    outcome           VARCHAR(16)  NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    reject_reason     VARCHAR(40),
    processed_at      TIMESTAMPTZ  NOT NULL,             -- 판정 시각 = 외부의 "처리일" (정산 파일은 이 날짜로 묶는다)
    CHECK ((outcome = 'REJECTED') = (reject_reason IS NOT NULL))
);

-- 정산 파일: 하루치를 처리 시각 순으로
CREATE INDEX idx_interbank_transfer_processed_at ON bankmock.interbank_transfer (processed_at);
