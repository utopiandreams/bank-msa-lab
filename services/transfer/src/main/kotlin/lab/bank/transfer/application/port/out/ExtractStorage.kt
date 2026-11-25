package lab.bank.transfer.application.port.out

/** 추출 파일 저장소 (구현: S3 호환 Garage). 같은 키에 두 번 쓰지 않는다: 저장소가 덮어쓰기를 못 막아서 키를 매번 새로 만든다 */
interface ExtractStorage {
    fun put(objectKey: String, content: ByteArray)
}
