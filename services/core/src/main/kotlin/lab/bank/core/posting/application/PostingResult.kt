package lab.bank.core.posting.application

import java.util.UUID

// 거래 처리 결과. 재시도해도 같은 entryId를 받는다
// 잔액은 넣지 않았다: 재시도 응답에서 "그때의 잔액"을 돌려주려면 거래 후 잔액을 따로 저장해야 해서 (필요해지면 그때)
data class PostingResult(
    val entryId: UUID,
    val replayed: Boolean,   // true: 이미 처리된 요청이라 이번엔 아무것도 바꾸지 않았다
)
