package lab.bank.transfer.domain

/**
 * 송금 금액 (원 단위, 항상 0보다 크다). 이 타입을 갖고 있다는 것 자체가 "이미 검증됐다"는 증거다 (parse, don't validate)
 * → 컨트롤러가 요청의 원시 값을 이 타입으로 바꾸는 순간 검증되고, 안쪽(use case, 도메인)은 금액을 다시 검사하지 않는다.
 *
 * core의 lab.bank.core.shared.Amount를 가져다 쓰지 않고 따로 둔다: 서비스끼리 코드를 공유하면 한쪽을 바꿀 때 둘 다 배포해야 한다.
 *
 * core와 달리 도메인 필드(Transfer.amount)로도 쓴다: transfer는 헥사고날이라 도메인이 JPA 엔티티가 아니다.
 * value class를 JPA 엔티티에 쓸 때의 문제(name mangling)는 TransferJpaEntity(Long)와 매퍼가 흡수한다.
 */
@JvmInline
value class Amount(val value: Long) {
    init {
        if (value <= 0) throw InvalidAmountException(value)
    }

    override fun toString(): String = "${value}원"
}
