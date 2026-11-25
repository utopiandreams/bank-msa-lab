package lab.bank.reconciliation.application

/** 받은 외부 원본을 저장소에 보관하는 포트. 같은 키에 두 번 쓰지 않는다 (키를 매번 새로) */
interface ExtractFileWriter {
    fun put(objectKey: String, content: ByteArray)
}
