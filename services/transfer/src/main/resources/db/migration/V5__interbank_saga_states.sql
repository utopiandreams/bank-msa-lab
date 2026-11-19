-- 5주 차 S-3: 타행 사가 상태 이름 확정 (2026-11-20 개발자 결정) + 취소 전표 칸
-- V4의 가칭(WITHDRAWN · INTERBANK_REQUESTED · COMPENSATING · CANCELLED)을 실제 상태 머신(InterbankTransferStatus)에 맞춘다.
-- 원칙: 원격 호출마다 "호출 전에 커밋하는 의도 상태" 하나. 결과 불명은 따로 두지 않고 의도 상태에 남는다 (pitfalls 27)
--
--   WITHDRAW_REQUESTED ─출금됨→ WITHDRAWN ─→ EXTERNAL_BANK_REQUESTED ─외부 수락→ COMPLETED
--        └─출금 거절→ FAILED                        └─외부 거절→ EXTERNAL_BANK_REJECTED(= 보상 의도) ─취소됨→ CANCELLED
--
-- V4 가칭 상태로 저장된 행은 없다 (사가 코드가 V5와 같은 배포에 처음 들어간다) → 데이터 변환 없이 제약만 바꾼다

-- 'EXTERNAL_BANK_REQUESTED'(23자)가 varchar(20)에 안 들어간다 (저장 어댑터 테스트가 잡음). 넓히는 건 테이블을 다시 쓰지 않는다
ALTER TABLE transfer.transfer ALTER COLUMN status TYPE varchar(30);

ALTER TABLE transfer.transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer.transfer ADD CONSTRAINT ck_transfer_status CHECK (status IN (
    -- 공통 (당행·타행)
    'REQUESTED', 'COMPLETED', 'FAILED',
    -- 당행
    'PROCESSING', 'UNKNOWN',
    -- 타행 사가
    'WITHDRAW_REQUESTED', 'WITHDRAWN', 'EXTERNAL_BANK_REQUESTED', 'EXTERNAL_BANK_REJECTED', 'CANCELLED'
));

-- 8주 차 해소 대상 = 끝나지 않고 멈출 수 있는 상태 전부. 스케줄러가 할 일이 상태마다 다르다:
--   WITHDRAW_REQUESTED      → 같은 송금 ID로 출금 재전송 (core 멱등)
--   WITHDRAWN               → 외부 송금 진행 (외부엔 확실히 안 나감)
--   EXTERNAL_BANK_REQUESTED → 외부에 결과 조회. 보상 금지
--   EXTERNAL_BANK_REJECTED  → 출금 취소 재시도 (취소 키 결정적)
-- REQUESTED는 저장되지 않는다 (생성 직후 의도 상태로 바꿔 저장)
DROP INDEX transfer.ix_transfer_unresolved;
CREATE INDEX ix_transfer_unresolved ON transfer.transfer (updated_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN',
                     'WITHDRAW_REQUESTED', 'WITHDRAWN', 'EXTERNAL_BANK_REQUESTED', 'EXTERNAL_BANK_REJECTED');

-- core 원장의 출금 취소(역분개) 전표 ID. CANCELLED일 때만 있다. 출금 전표는 공통 테이블의 core_entry_id
-- "CANCELLED면 NOT NULL" 제약은 상태가 다른 테이블(transfer)에 있어 CHECK로 걸 수 없다 → 도메인(cancelled(cancelEntryId))이 보장
ALTER TABLE transfer.interbank_transfer ADD COLUMN cancel_entry_id uuid;
