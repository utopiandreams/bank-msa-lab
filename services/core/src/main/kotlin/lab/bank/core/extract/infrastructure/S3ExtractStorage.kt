package lab.bank.core.extract.infrastructure

import lab.bank.core.extract.application.ExtractStorage
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Path

/**
 * ExtractStorage 구현: S3 PutObject 한 번 (원자적: 다 올라가야 보인다). 파일에서 흘려 보낸다 (메모리에 안 올림).
 * PutObject 한 번은 5GB까지. 그보다 크거나, 끊겼을 때 이어 올리려면 멀티파트 업로드 (지금 규모는 한 번으로 충분)
 */
@Component
class S3ExtractStorage(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
) : ExtractStorage {

    override fun putFile(objectKey: String, file: Path) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(properties.bucket).key(objectKey).contentType("text/csv").build(),
            RequestBody.fromFile(file),
        )
    }
}
