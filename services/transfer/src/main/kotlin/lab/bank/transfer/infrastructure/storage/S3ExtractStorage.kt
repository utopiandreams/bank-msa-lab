package lab.bank.transfer.infrastructure.storage

import lab.bank.transfer.application.port.out.ExtractStorage
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** ExtractStorage 구현: S3 PutObject 한 번 (원자적: 다 올라가야 보인다). 예외는 그대로 던진다 → 추출 실패 */
@Component
class S3ExtractStorage(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
) : ExtractStorage {

    override fun put(objectKey: String, content: ByteArray) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(properties.bucket).key(objectKey).contentType("text/csv").build(),
            RequestBody.fromBytes(content),
        )
    }
}
