package lab.bank.reconciliation.infrastructure.storage

import lab.bank.reconciliation.application.ExtractFileException
import lab.bank.reconciliation.application.ExtractFileReader
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/** ExtractFileReader 구현: S3 GetObject. 알림은 왔는데 파일이 없으면 파일 불량 (생산자가 순서를 어겼거나 지워졌다) */
@Component
class S3ExtractFileReader(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
) : ExtractFileReader {

    override fun read(objectKey: String): ByteArray =
        try {
            s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket).key(objectKey).build()).asByteArray()
        } catch (e: NoSuchKeyException) {
            throw ExtractFileException("알림은 왔는데 파일이 없음: $objectKey", e)
        }
}
