package lab.bank.reconciliation.infrastructure.storage

import lab.bank.reconciliation.application.ExtractFileWriter
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** 받은 외부 원본을 저장소에 그대로 보관 (S3 PutObject 한 번) */
@Component
class S3ExtractFileWriter(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
) : ExtractFileWriter {

    override fun put(objectKey: String, content: ByteArray) {
        s3Client.putObject(PutObjectRequest.builder().bucket(properties.bucket).key(objectKey).contentType("text/plain").build(), RequestBody.fromBytes(content))
    }
}
