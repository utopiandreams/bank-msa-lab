package lab.bank.transfer.application.port.out

import lab.bank.common.event.ExtractCompletedEvent

/** 추출 완료 알림 발행. 브로커가 받았다고 확인할 때까지 기다리고, 실패하면 예외 (추출 배치가 실패로 끝나야 다시 돌린다) */
interface ExtractCompletedPublisher {
    fun publish(event: ExtractCompletedEvent)
}
