package lab.bank.transfer.domain

/**
 * 금융기관 코드 (금융결제원이 부여하는 3자리 숫자. 예: 004 국민, 088 신한, 090 카카오뱅크).
 * 이 타입을 갖고 있으면 형식은 이미 검증됐다 (parse, don't validate, Amount와 같은 방식) → 잘못된 형식은 생성 순간 400.
 *
 * enum이 아닌 이유 (2026-11-20 결정): 실무에서 기관 코드는 합병·신설로 바뀌는 기준 정보(코드 테이블)다.
 * enum이면 바뀔 때마다 재배포해야 하고, 폐지된 코드로 저장된 과거 거래를 읽는 순간 매핑 예외가 난다 (거래는 불변이라 못 지움).
 * 여기서는 형식만 보고, "실제로 있는 기관인가"는 외부 결제망(bank-mock)이 판단한다. 필요해지면 기관 목록 포트를 둔다.
 *
 * "우리 은행이면 안 된다"는 이 타입의 규칙이 아니다: 999도 올바른 은행 코드다. 그건 타행 송금의 규칙 (InterbankTransfer).
 */
@JvmInline
value class BankCode(val value: String) {
    init {
        if (!FORMAT.matches(value)) throw InvalidBankCodeException(value, "숫자 3자리")
    }

    // 우리 은행 코드면 당행 (ADR-0004 결정 6)
    val isOurs: Boolean get() = value == OURS_VALUE

    override fun toString(): String = value

    companion object {
        // 선언 순서 주의: FORMAT이 OURS보다 먼저 초기화돼야 OURS를 만들 때 init이 FORMAT을 쓸 수 있다
        private val FORMAT = Regex("^\\d{3}$")

        // 우리 은행 코드: 실제 은행과 겹치지 않게 비어 있는 999로 둔다 (학습용 가정)
        private const val OURS_VALUE = "999"
        val OURS = BankCode(OURS_VALUE)
    }
}
