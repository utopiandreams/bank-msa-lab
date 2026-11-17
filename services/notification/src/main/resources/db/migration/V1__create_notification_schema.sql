-- notification 서비스 스키마: 보낸(보낼) 알림 기록 (ADR-0003)
-- DB per service: notification-db는 다른 서비스 DB와 완전히 분리. 송금·계좌에 FK를 걸 수 없다 (이벤트로만 안다)
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notification (
    id           uuid         PRIMARY KEY,
    event_id     uuid         NOT NULL,   -- 이 알림을 만든 이벤트. 같은 이벤트가 두 번 와도 알림은 하나 (멱등 컨슈머)
    transfer_id  uuid         NOT NULL,
    recipient_id varchar(64)  NOT NULL,   -- 받는 사람 (송금 요청자). 진짜 발송이면 여기서 연락처를 조회한다
    type         varchar(30)  NOT NULL,   -- TRANSFER_COMPLETED | TRANSFER_FAILED
    message      varchar(500) NOT NULL,
    status       varchar(20)  NOT NULL,   -- PENDING → SENT (외부 발송 실패·재시도는 15주 차)
    created_at   timestamptz  NOT NULL,
    sent_at      timestamptz,
    version      bigint       NOT NULL,   -- 낙관적 락 (15주 차 재발송 스케줄러와 경합 대비) + 새 엔티티 판별

    -- 멱등의 최종 방어선: 같은 이벤트의 두 번째 INSERT가 여기서 막힌다 (T-2 고객 멱등 키와 같은 방식, 어댑터가 제약 이름으로 식별)
    CONSTRAINT uq_notification_event_id UNIQUE (event_id),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_sent_has_time CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

-- 15주 차 재발송 대상 찾기: 멈춘 PENDING만 (부분 인덱스)
CREATE INDEX ix_notification_pending ON notification.notification (created_at) WHERE status = 'PENDING';
