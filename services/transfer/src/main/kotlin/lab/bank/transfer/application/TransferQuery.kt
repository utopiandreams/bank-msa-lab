package lab.bank.transfer.application

import lab.bank.transfer.domain.Transfer
import java.util.UUID

// 송금 조회. 명령(TransferUseCase)과 나눈 이유: 조회는 상태를 바꾸지 않고 트랜잭션 경계 고민도 없어서 따로 두는 게 읽기 쉽다
interface TransferQuery {

    fun find(id: UUID): Transfer?
}
