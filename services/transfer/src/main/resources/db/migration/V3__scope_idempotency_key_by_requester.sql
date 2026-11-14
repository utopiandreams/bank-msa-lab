-- 고객 멱등 키를 사용자별로 (ADR-0002 구현 전 결정 1). 키 범위: 전역 → (요청자, 키)
--
-- 왜: 전역이면 다른 사용자가 우연히 같은 키를 쓸 때
--   - 내용이 다르면 정상 송금이 409로 거절되고 ("이 키를 누가 썼다"는 정보도 샌다)
--   - 내용까지 같으면 남의 송금이 200으로 그대로 보인다
--   (2026-11-14 클러스터에서 재현: 영희가 철수와 같은 키로 보내 철수의 transferId를 받음)
--
-- 요청자 = gateway가 검증한 토큰의 sub (X-User-Id 헤더)

-- V2와 같은 3단계: NULL 허용으로 추가 → 기존 행 채우기 → NOT NULL
ALTER TABLE transfer.transfer ADD COLUMN requester_id varchar(64);

-- 인증 전에 만들어진 송금: 요청자 기록이 없다. 'legacy'로 표시한다 (진짜 요청자를 찾으려면 core의 계좌 소유자를
-- 물어봐야 하는데, Flyway SQL은 transfer DB 밖을 못 본다 = DB per service. 서비스 간 백필은 별도 배치가 필요)
-- 기존 키는 'legacy-<송금ID>'로 이미 유일해서 (legacy, 키) UNIQUE에 충돌하지 않는다
UPDATE transfer.transfer SET requester_id = 'legacy' WHERE requester_id IS NULL;

ALTER TABLE transfer.transfer ALTER COLUMN requester_id SET NOT NULL;

-- 전역 UNIQUE를 (요청자, 키) UNIQUE로 교체. 같은 사용자의 같은 키 동시 요청은 여전히 늦은 쪽 INSERT가 막힌다
-- 컬럼 순서 (requester_id, idempotency_key): 이 제약이 만드는 인덱스가 "요청자 + 키" 조회에 그대로 쓰인다
ALTER TABLE transfer.transfer DROP CONSTRAINT uq_transfer_idempotency_key;
ALTER TABLE transfer.transfer ADD CONSTRAINT uq_transfer_requester_idempotency_key UNIQUE (requester_id, idempotency_key);
