-- transfer 서비스 스키마: 송금 요청 기록과 상태 머신 (ADR-0001)
-- DB per service: transfer-db는 core-db와 완전히 다른 DB다. 계좌 테이블에 FK를 걸 수 없다 (계좌는 core 소유)
CREATE SCHEMA IF NOT EXISTS transfer;

CREATE TABLE transfer.transfer (
    id              uuid        PRIMARY KEY,   -- 송금 ID = core에 보내는 Idempotency-Key
    from_account_id uuid        NOT NULL,
    to_account_id   uuid        NOT NULL,
    amount          bigint      NOT NULL,
    status          varchar(20) NOT NULL,
    failure_code    varchar(50),
    core_entry_id   uuid,                      -- core 원장 거래 ID (완료일 때). transfer ↔ core 대사의 연결 고리
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,

    -- 앱 검증의 마지막 방어선 (도메인 메서드와 같은 규칙)
    CONSTRAINT ck_transfer_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfer_not_same_account CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_transfer_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    -- 상태별로 있어야 하는 값: 완료면 core 거래 ID, 실패면 사유
    CONSTRAINT ck_transfer_completed_has_entry CHECK (status <> 'COMPLETED' OR core_entry_id IS NOT NULL),
    CONSTRAINT ck_transfer_failed_has_code CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);

-- 8주 차 해소 대상 찾기: "확인필요 + 오래된 처리중"만 훑는다. 끝난 송금(대부분)은 인덱스에 안 넣는다 (부분 인덱스)
CREATE INDEX ix_transfer_unresolved ON transfer.transfer (updated_at) WHERE status IN ('PROCESSING', 'UNKNOWN');
