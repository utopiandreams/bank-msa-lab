package lab.bank.transfer

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.transfer.application.port.out.ExtractCompletedPublisher
import lab.bank.transfer.application.port.out.ExtractStorage
import java.nio.file.Files
import java.nio.file.Path

/** 가짜 저장소: 올린 파일 내용을 메모리에 담는다 */
class FakeExtractStorage : ExtractStorage {
    val objects = linkedMapOf<String, ByteArray>()
    var onPut: (String) -> Unit = {}

    override fun putFile(objectKey: String, file: Path) {
        onPut(objectKey)
        objects[objectKey] = Files.readAllBytes(file)
    }

    fun reset() { objects.clear(); onPut = {} }
}

/** 가짜 발행: 보낸 이벤트를 담는다 */
class FakeExtractCompletedPublisher : ExtractCompletedPublisher {
    val events = mutableListOf<ExtractCompletedEvent>()

    override fun publish(event: ExtractCompletedEvent) { events += event }

    fun reset() = events.clear()
}
