-- 5주 차 타행 이체 (ADR-0004): 미정산 계정, 타행 출금·취소 거래, 취소 대상 전표
-- 기존 행은 건드리지 않는다 (제약을 넓히고 nullable 컬럼만 추가)

-- 취소 전표 키는 core가 "{원래 키}-cancel"로 만든다 → 64자 키에 7자가 붙어도 들어가게
ALTER TABLE ledger.journal_entry ALTER COLUMN idempotency_key TYPE varchar(80);
-- 'INTERBANK_WITHDRAWAL_CANCEL'(27자)이 20자에 안 들어간다
ALTER TABLE ledger.journal_entry ALTER COLUMN type TYPE varchar(30);

ALTER TABLE ledger.journal_entry DROP CONSTRAINT ck_journal_entry_type;
ALTER TABLE ledger.journal_entry ADD CONSTRAINT ck_journal_entry_type
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'INTERBANK_WITHDRAWAL', 'INTERBANK_WITHDRAWAL_CANCEL'));

-- 이 전표가 취소하는 원래 전표. UNIQUE = 한 전표는 한 번만 취소된다 (자연 키, 요청자의 멱등 키를 믿지 않아도 됨)
-- 같은 테이블(같은 애그리거트 종류)이라 FK를 건다: 없는 전표를 취소한 기록이 생기지 않게
ALTER TABLE ledger.journal_entry ADD COLUMN reverses_entry_id uuid REFERENCES ledger.journal_entry (id);
ALTER TABLE ledger.journal_entry ADD CONSTRAINT uk_journal_entry_reverses_entry_id UNIQUE (reverses_entry_id);
-- 취소 전표에만 원래 전표가 있다 (JournalEntry.cancelOf와 같은 규칙)
ALTER TABLE ledger.journal_entry ADD CONSTRAINT ck_journal_entry_reverses
    CHECK ((type = 'INTERBANK_WITHDRAWAL_CANCEL') = (reverses_entry_id IS NOT NULL));

ALTER TABLE ledger.journal_line DROP CONSTRAINT ck_journal_line_ledger_account;
ALTER TABLE ledger.journal_line ADD CONSTRAINT ck_journal_line_ledger_account
    CHECK (ledger_account IN ('CASH', 'CUSTOMER_DEPOSIT', 'INTERBANK_UNSETTLED'));
