package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Transfer
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.util.UUID

// 저장 포트의 JPA 구현 (아웃바운드 어댑터)
@Component
class TransferPersistenceAdapter(
    private val jpaRepository: TransferJpaRepository,
) : TransferRepository {

    // 매번 새 엔티티 객체를 만들어 저장한다 (version이 있으면 merge → 버전 비교 → 다르면 낙관적 락 예외).
    // saveAndFlush: UPDATE를 지금 보내야 올라간 version을 돌려줄 수 있다 (flush 전엔 version이 그대로)
    override fun save(transfer: Transfer): Transfer =
        TransferMapper.toDomain(jpaRepository.saveAndFlush(TransferMapper.toEntity(transfer)))

    override fun findById(id: UUID): Transfer? =
        jpaRepository.findByIdOrNull(id)?.let(TransferMapper::toDomain)
}
