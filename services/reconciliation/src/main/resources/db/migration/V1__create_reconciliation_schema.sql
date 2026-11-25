-- reconciliation 서비스 스키마 (ADR-0006). DB per service: 다른 서비스 DB와 완전히 분리, 데이터는 추출 파일로만 받는다
-- 테이블(회차·예외 항목·인박스·스테이징)은 V2부터 (대사 본체 구현 때)
CREATE SCHEMA IF NOT EXISTS reconciliation;
