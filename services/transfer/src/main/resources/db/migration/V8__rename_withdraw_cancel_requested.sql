-- 8주 차 2부 (설계 연습 08 결정 3): 보상 의도 상태 이름 EXTERNAL_BANK_REJECTED → WITHDRAW_CANCEL_REQUESTED
-- 이유: 의도 상태는 "지금 무엇을 요청 중인가"(행위)로 짓는다 (pitfalls 39). 해소 일꾼이 외부에 보내지 않은 송금
-- (WITHDRAW_REQUESTED·WITHDRAWN)도 이 상태로 보내게 되면서 "외부가 거절했다"는 이름이 거짓말이 된다. 왜 취소하나는 사유 칸에
--
-- 순서: 옛 이름을 허용하는 제약을 먼저 지우고 → 행을 바꾸고 → 새 제약. 한 마이그레이션 = 한 트랜잭션(PostgreSQL DDL도 트랜잭션)이라
-- 중간 상태가 남지 않는다. 배포 중 옛 버전 파드가 옛 이름을 쓰면 새 제약에 막힌다 → 롤링 중 잠깐 타행 보상이 실패할 수 있다
-- (학습 클러스터라 감수. 실무라면 확장(둘 다 허용) → 코드 배포 → 축소(옛 이름 제거) 두 단계로 나눈다: pitfalls 18 계약 넓히기와 같은 원리)

ALTER TABLE transfer.transfer DROP CONSTRAINT ck_transfer_status;

UPDATE transfer.transfer SET status = 'WITHDRAW_CANCEL_REQUESTED' WHERE status = 'EXTERNAL_BANK_REJECTED';

ALTER TABLE transfer.transfer ADD CONSTRAINT ck_transfer_status CHECK (status IN (
    -- 공통 (당행·타행)
    'REQUESTED', 'COMPLETED', 'FAILED',
    -- 당행
    'PROCESSING', 'UNKNOWN',
    -- 타행 사가
    'WITHDRAW_REQUESTED', 'WITHDRAWN', 'EXTERNAL_BANK_REQUESTED', 'WITHDRAW_CANCEL_REQUESTED', 'CANCELLED'
));

-- 해소 대상 부분 인덱스도 새 이름으로 (끝나지 않고 멈출 수 있는 상태 전부)
DROP INDEX transfer.ix_transfer_unresolved;
CREATE INDEX ix_transfer_unresolved ON transfer.transfer (updated_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN',
                     'WITHDRAW_REQUESTED', 'WITHDRAWN', 'EXTERNAL_BANK_REQUESTED', 'WITHDRAW_CANCEL_REQUESTED');
