-- ledger 모듈 스키마: 복식부기 원장. 거래(journal_entry) 1 : 분개 줄(journal_line) N
-- 원장은 쌓이기만 하는 기록이라, 앱 검증에 더해 DB에도 제약을 건다 (앱 버그의 마지막 방어선)
CREATE SCHEMA IF NOT EXISTS ledger;

CREATE TABLE ledger.journal_entry (
    id              uuid        PRIMARY KEY,
    idempotency_key varchar(64) NOT NULL,
    type            varchar(20) NOT NULL,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,

    -- 같은 요청이 두 번 와도 거래는 하나만 (멱등의 최종 방어선)
    CONSTRAINT uk_journal_entry_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_journal_entry_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER'))
);

CREATE TABLE ledger.journal_line (
    id             uuid        PRIMARY KEY,
    -- 같은 애그리거트라 FK를 건다: 주인 없는 줄이 생기지 않게
    entry_id       uuid        NOT NULL REFERENCES ledger.journal_entry (id),
    ledger_account varchar(30) NOT NULL,
    -- 고객 계좌 ID. account 모듈의 테이블이라 FK는 걸지 않는다 (모듈 경계, 정합성은 앱 + 대사)
    account_id     uuid,
    direction      varchar(10) NOT NULL,
    amount         bigint      NOT NULL,

    CONSTRAINT ck_journal_line_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_journal_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_journal_line_ledger_account CHECK (ledger_account IN ('CASH', 'CUSTOMER_DEPOSIT')),
    -- 고객예금 계정에만 계좌 ID가 있다 (JournalLine.init과 같은 규칙)
    CONSTRAINT ck_journal_line_account_id CHECK ((ledger_account = 'CUSTOMER_DEPOSIT') = (account_id IS NOT NULL))
);

-- 거래별 줄 조회 (거래 → 줄 지연 로딩). PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다
CREATE INDEX ix_journal_line_entry_id ON ledger.journal_line (entry_id);

-- 계좌별 줄 조회 (대사: 계좌 잔액 = 그 계좌 줄의 합). 현금 줄은 account_id가 NULL이라 부분 인덱스로 뺀다
CREATE INDEX ix_journal_line_account_id ON ledger.journal_line (account_id) WHERE account_id IS NOT NULL;
