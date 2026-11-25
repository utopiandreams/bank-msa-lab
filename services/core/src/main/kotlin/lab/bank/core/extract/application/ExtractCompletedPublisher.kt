package lab.bank.core.extract.application

import lab.bank.common.event.ExtractCompletedEvent

/** 추출 완료 알림 (구현: infrastructure/KafkaExtractCompletedPublisher). 브로커 확인까지 기다리고 실패하면 예외 */
interface ExtractCompletedPublisher {
    fun publish(event: ExtractCompletedEvent)
}
