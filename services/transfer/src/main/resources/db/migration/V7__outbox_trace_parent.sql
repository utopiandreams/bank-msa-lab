-- 9주 차 트레이싱: outbox 행이 "어느 요청에서 왔나"를 들고 간다 (2026-11-24)
-- 문제: 요청 트레이스는 outbox INSERT에서 끝나고, 릴레이가 나중에 다른 스레드에서 새 트레이스로 발행한다
--       (Kafka는 메시지 헤더로 trace 문맥을 넘기지만 DB 행에는 그 칸이 없었다)
-- 해결: INSERT 때 W3C traceparent("00-<trace ID>-<부모 span ID>-<샘플링 플래그>")를 적고,
--       릴레이가 행마다 꺼내 그 요청의 자식으로 발행한다
-- NULL 허용: 이 컬럼 전에 쌓인 행, 에이전트 없이 도는 테스트·로컬 → 릴레이는 새 트레이스로 발행한다
ALTER TABLE transfer.outbox_event ADD COLUMN trace_parent varchar(64);
