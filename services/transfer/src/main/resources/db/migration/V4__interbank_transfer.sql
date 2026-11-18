-- 5주 차 타행 이체 (ADR-0004): "공통 + 상세" 구조 (2026-11-19 개발자 결정)
--   transfer.transfer          = 송금 요청 공통 (요청자, 고객 멱등 키, 보내는 계좌, 금액, 상태, 종류)
--   transfer.interbank_transfer = 타행에만 있는 칸 (받는 은행·계좌번호, 외부 거절 사유. 앞으로 전문 번호·정산일)
-- 고객 멱등 키 UNIQUE가 공통 테이블에 있어서 당행·타행을 통틀어 (요청자, 키)당 송금 하나가 그대로 보장된다
-- (테이블을 완전히 나누면 UNIQUE가 테이블을 넘지 못해 같은 키로 당행 하나, 타행 하나가 생길 수 있다)

-- 송금 종류. 기존 행은 전부 당행. DEFAULT가 있어 NOT NULL을 한 번에 추가해도 기존 행이 채워진다
-- (V2의 3단계가 필요 없는 경우: 기존 행에 넣을 값이 상수 하나일 때. PostgreSQL 11+는 테이블을 다시 쓰지 않는다)
ALTER TABLE transfer.transfer ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'DOMESTIC';
ALTER TABLE transfer.transfer ADD CONSTRAINT ck_transfer_kind CHECK (kind IN ('DOMESTIC', 'INTERBANK'));

-- 받는 계좌(우리 은행 계좌 ID)는 당행에만 있다. 타행은 interbank_transfer의 계좌번호(문자열)
ALTER TABLE transfer.transfer ALTER COLUMN to_account_id DROP NOT NULL;
ALTER TABLE transfer.transfer ADD CONSTRAINT ck_transfer_domestic_has_to_account
    CHECK ((kind = 'DOMESTIC') = (to_account_id IS NOT NULL));
-- ck_transfer_not_same_account(from <> to)는 그대로: to가 NULL이면 CHECK 결과가 NULL이라 통과한다

-- 타행 사가 상태 (ADR-0004 결정 3). CANCELLED는 가칭: 이름을 바꾸면 V5로
ALTER TABLE transfer.transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer.transfer ADD CONSTRAINT ck_transfer_status CHECK (status IN (
    'REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN',
    'WITHDRAWN', 'INTERBANK_REQUESTED', 'COMPENSATING', 'CANCELLED'
));

-- 8주 차 해소 대상: 사가 중간에 멈춘 상태도 넣는다 (끝난 송금은 여전히 인덱스 밖)
DROP INDEX transfer.ix_transfer_unresolved;
CREATE INDEX ix_transfer_unresolved ON transfer.transfer (updated_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN', 'WITHDRAWN', 'INTERBANK_REQUESTED', 'COMPENSATING');

CREATE TABLE transfer.interbank_transfer (
    -- 공통 송금과 1:1. 송금 ID가 곧 bank-mock에 보내는 멱등 키
    transfer_id            uuid        PRIMARY KEY REFERENCES transfer.transfer (id),
    to_bank_code           varchar(10) NOT NULL,
    to_account_number      varchar(30) NOT NULL,
    external_reject_reason varchar(50),            -- bank-mock이 거절한 사유 (ACCOUNT_NOT_FOUND 등). 거절됐을 때만
    created_at             timestamptz NOT NULL
);
