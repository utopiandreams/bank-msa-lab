-- 고객 멱등 키 (T-2). 같은 키로 다시 온 송금 요청은 새로 만들지 않고 기존 송금을 돌려준다
--
-- 이미 데이터가 있는 테이블에 NOT NULL 컬럼을 추가하는 순서 (실무에서 자주 쓰는 3단계):
--   1) NULL 허용으로 추가 → 2) 기존 행 채우기 → 3) NOT NULL로 바꾸기
-- 처음부터 NOT NULL로 추가하면 기존 행(클러스터에 이미 있는 송금)이 값이 없어서 마이그레이션이 실패한다
ALTER TABLE transfer.transfer ADD COLUMN idempotency_key varchar(64);

-- 키 없이 만들어진 기존 송금: 송금 ID로 채운다. 'legacy-' 접두사로 V2 이전 데이터임을 표시 (고객이 보낼 리 없는 값)
UPDATE transfer.transfer SET idempotency_key = 'legacy-' || id::text WHERE idempotency_key IS NULL;

ALTER TABLE transfer.transfer ALTER COLUMN idempotency_key SET NOT NULL;

-- 최종 방어선: 같은 키 동시 요청은 늦은 쪽 INSERT가 여기서 막힌다 (어댑터가 이 제약 이름으로 식별)
-- 키 범위: 지금은 전역. gateway JWT 인증이 생기면 (사용자 ID, 키)로 바꾼다
ALTER TABLE transfer.transfer ADD CONSTRAINT uq_transfer_idempotency_key UNIQUE (idempotency_key);
