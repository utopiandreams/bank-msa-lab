package lab.bank.transfer.application.port.out

import java.nio.file.Path

/**
 * 추출 파일 저장소 (구현: S3 호환 Garage). 메모리가 아니라 **디스크의 파일**을 올린다 (100만 건이면 파일이 수백 MB).
 * 같은 키에 두 번 쓰지 않는다: 저장소가 덮어쓰기를 못 막아서 키를 매번 새로 만든다
 */
interface ExtractStorage {
    fun putFile(objectKey: String, file: Path)
}
