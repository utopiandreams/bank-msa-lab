package lab.bank.reconciliation.application

/** 저장소(Garage)에서 추출 파일을 읽는 포트 */
interface ExtractFileReader {
    fun read(objectKey: String): ByteArray
}
