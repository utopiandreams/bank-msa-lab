package lab.bank.core.extract.application

import java.nio.file.Path

/** 추출 파일 저장소 (구현: infrastructure/S3ExtractStorage, Garage). 디스크의 파일을 올린다. 같은 키에 두 번 쓰지 않는다 (키를 매번 새로) */
interface ExtractStorage {
    fun putFile(objectKey: String, file: Path)
}
