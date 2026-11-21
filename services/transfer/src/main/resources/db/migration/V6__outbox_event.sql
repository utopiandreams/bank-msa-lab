-- 6주 차 Transactional Outbox: 송금 상태 변경과 "보낼 이벤트"를 같은 트랜잭션으로 커밋한다
-- 릴레이(폴링 스케줄러)가 미발행 행을 FOR UPDATE SKIP LOCKED로 가져가 Kafka로 보내고 published_at을 채운다
-- 발행 완료 행은 일일 정리 배치가 N일 뒤 지운다 (추적·재발행의 단서로 잠시 남긴다)

CREATE TABLE transfer.outbox_event (
    -- = 이벤트의 eventId. INSERT 때 한 번 정하고 재시도해도 같은 값으로 보낸다
    --   (보낼 때마다 새로 만들면 컨슈머 멱등(event_id UNIQUE)이 중복을 못 거른다, pitfalls 29)
    id            uuid         PRIMARY KEY,
    aggregate_id  uuid         NOT NULL,   -- 이 이벤트를 만든 송금 ID (추적용)
    topic         varchar(100) NOT NULL,
    message_key   varchar(100) NOT NULL,   -- Kafka 메시지 키 (= 송금 ID, ADR-0003: 순서가 필요한 단위)
    payload       text         NOT NULL,   -- 이벤트 JSON 그대로. 릴레이는 내용을 모르고 옮기기만 한다
    created_at    timestamptz  NOT NULL,
    published_at  timestamptz,             -- NULL = 아직 안 보냄
    version       bigint       NOT NULL
);

-- 릴레이 조회: "미발행을 오래된 순으로". 발행 완료 행이 쌓여도 미발행만 훑도록 부분 인덱스
-- (MySQL이었다면 이 인덱스가 없을 때 잠금 읽기가 테이블 전체를 잠근다. PostgreSQL은 반환된 행만 잠그지만 훑는 비용은 같다)
CREATE INDEX ix_outbox_event_unpublished ON transfer.outbox_event (created_at) WHERE published_at IS NULL;

-- 정리 배치: "발행한 지 N일 지난 행"
CREATE INDEX ix_outbox_event_published_at ON transfer.outbox_event (published_at) WHERE published_at IS NOT NULL;
