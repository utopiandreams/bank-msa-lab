-- account 모듈 스키마. ledger 모듈은 ledger 스키마를 따로 쓴다 (서로의 테이블을 직접 건드리지 않는다)
CREATE SCHEMA IF NOT EXISTS account;

CREATE TABLE account.accounts (
    id             uuid        PRIMARY KEY,
    owner_id       varchar(64) NOT NULL,
    balance        bigint      NOT NULL DEFAULT 0,   -- 원 단위. CHECK (balance >= 0)을 걸지는 티켓 질문 1 참고
    last_ledger_id uuid,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL
);

-- "내 계좌 목록" 조회용
CREATE INDEX ix_accounts_owner_id ON account.accounts (owner_id);
