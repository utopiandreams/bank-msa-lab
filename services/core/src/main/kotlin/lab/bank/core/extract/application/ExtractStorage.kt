package lab.bank.core.extract.application

/** 추출 파일 저장소 (구현: infrastructure/S3ExtractStorage, Garage). 같은 키에 두 번 쓰지 않는다 (키를 매번 새로) */
interface ExtractStorage {
    fun put(objectKey: String, content: ByteArray)
}
