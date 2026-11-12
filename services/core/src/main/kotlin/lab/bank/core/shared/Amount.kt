package lab.bank.core.shared

/**
 * 거래 금액 (원 단위, 항상 0보다 크다). 이 타입을 갖고 있다는 것 자체가 "이미 검증됐다"는 증거다
 * → 안쪽 계층(posting·account·ledger)은 금액을 다시 검사하지 않는다 (parse, don't validate)
 *
 * 잔액은 이 타입이 아니다: 잔액은 0원일 수 있고(새 계좌), 거래 금액과 규칙이 다르다.
 *
 * value class: 컴파일되면 Long으로 풀려서 객체를 만들지 않는다. 대신 JPA 엔티티 필드로는 쓰기 어려워서
 * (메서드 이름이 바뀌는 name mangling) 메서드 시그니처까지만 쓰고, 엔티티는 Long으로 저장한다.
 */
@JvmInline
value class Amount(val value: Long) {
    init {
        if (value <= 0) throw InvalidAmountException(value)
    }

    override fun toString(): String = "${value}원"
}
