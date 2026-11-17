// 공통 라이브러리: 서비스끼리 주고받는 이벤트 스키마만 둔다 (CLAUDE.md 4장 "최소한으로 유지").
// 프레임워크 의존 없음 (순수 Kotlin). 유틸·공통 DTO·헬퍼는 넣지 않는다.
plugins {
	id("bank-msa-lab")
	kotlin("jvm") version "2.3.21"
}

group = "lab.bank"
version = "0.0.1-SNAPSHOT"
