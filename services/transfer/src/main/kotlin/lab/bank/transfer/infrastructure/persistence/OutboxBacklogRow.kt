package lab.bank.transfer.infrastructure.persistence

// OutboxJpaRepository.backlog()의 결과 (네이티브 쿼리 인터페이스 프로젝션: 컬럼 별칭 = getter 이름)
interface OutboxBacklogRow {
    val unpublished: Long
    val oldestAgeSeconds: Double
}
