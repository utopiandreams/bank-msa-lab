-- 대사 서비스 테이블 (ADR-0006, 설계 연습 09)

-- 인박스: 추출 완료 알림을 받은 기록 (결정 15). 컨슈머는 여기 넣기만 하고 바로 커밋한다. 같은 알림이 두 번 와도 extract_id PK로 한 번
CREATE TABLE reconciliation.extract_arrival (
    extract_id    uuid         PRIMARY KEY,
    source        varchar(20)  NOT NULL,      -- transfer | core (외부는 수신 작업이 넣는다, 결정 19)
    business_date date         NOT NULL,
    object_key    varchar(200) NOT NULL,
    row_count     bigint       NOT NULL,      -- 아래 셋은 정산 파일 trailer 역할: 받은 파일과 대조 (결정 12)
    amount_sum    bigint       NOT NULL,
    sha256        char(64)     NOT NULL,
    debit_sum     bigint,                     -- core만: 그날 원장 차변 합·대변 합 (시산표)
    credit_sum    bigint,
    extracted_at  timestamptz  NOT NULL,
    received_at   timestamptz  NOT NULL
);
-- (날짜, 출처)마다 가장 마지막에 도착한 추출을 쓴다
CREATE INDEX ix_extract_arrival_latest ON reconciliation.extract_arrival (business_date, source, received_at DESC);

-- 대사 회차 (결정 10·17). 전부 아니면 전무라 DONE 행은 결과와 같은 트랜잭션에서만 생긴다 (진행 중 상태를 남기지 않는다: 죽으면 처음부터)
CREATE TABLE reconciliation.recon_run (
    id                   uuid        PRIMARY KEY,
    business_date        date        NOT NULL,
    status               varchar(20) NOT NULL,   -- DONE | FAILED(파일 불량 등) | SUPERSEDED(같은 날짜를 새 추출로 다시 돌림)
    transfer_extract_id  uuid        NOT NULL,
    core_extract_id      uuid        NOT NULL,
    logic_version        int         NOT NULL,   -- 판정 규칙이 바뀌면 올린다: 옛 날짜를 다시 돌려 결과가 달라지면 이 값으로 설명 (결정 10 재현 조건)
    transfer_rows        bigint,
    core_rows            bigint,
    carried_in           int,                    -- 이 회차에 들어온 이월 항목 수
    matched              int,
    open_items           int,                    -- 이 회차가 끝난 뒤 열린(이월) 항목 수
    escalated_items      int,                    -- 경보 상태 항목 수
    trial_balance_ok     boolean,
    failure_reason       varchar(500),
    started_at           timestamptz NOT NULL,
    finished_at          timestamptz NOT NULL,
    CONSTRAINT ck_recon_run_status CHECK (status IN ('DONE', 'FAILED', 'SUPERSEDED'))
);
CREATE INDEX ix_recon_run_date ON reconciliation.recon_run (business_date, status);

-- 예외 항목 = 이월 풀 (결정 6·10·14). 정상 건은 남기지 않는다
CREATE TABLE reconciliation.recon_item (
    id                 uuid          PRIMARY KEY,
    pair               varchar(30)   NOT NULL,   -- TRANSFER_CORE (외부 쌍은 2단계)
    item_key           varchar(120)  NOT NULL,   -- 송금 ID, 송금 없는 전표는 "entry:<전표 키>", 시산표는 "trial:<날짜>"
    transfer_id        uuid,
    kind               varchar(30)   NOT NULL,   -- ItemKind
    status             varchar(20)   NOT NULL,   -- OPEN(이월) | ESCALATED(경보) | RESOLVED(풀림)
    detail             varchar(1000) NOT NULL,
    snapshot           jsonb         NOT NULL,   -- 다음 날 다시 판정할 원본 줄들 (CarrySnapshot)
    first_seen_date    date          NOT NULL,
    first_seen_run_id  uuid          NOT NULL,
    escalated_run_id   uuid,
    resolved_run_id    uuid,
    resolved_date      date,
    updated_at         timestamptz   NOT NULL,
    CONSTRAINT ck_recon_item_status CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED'))
);
-- 같은 대상의 살아 있는 항목은 하나 (풀린 항목은 이력으로 여러 개 남을 수 있다)
CREATE UNIQUE INDEX uq_recon_item_active ON reconciliation.recon_item (pair, item_key) WHERE status IN ('OPEN', 'ESCALATED');
