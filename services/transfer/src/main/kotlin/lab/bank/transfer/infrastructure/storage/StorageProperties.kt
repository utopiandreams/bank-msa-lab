package lab.bank.transfer.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 대사 추출 파일 저장소 (Garage, S3 호환) 접속 정보 (application.yaml의 storage.*, ADR-0006).
 * 클러스터: deploy/apps/transfer/values.yaml의 STORAGE_* (키는 Secret garage-secrets)
 */
@ConfigurationProperties(prefix = "storage")
data class StorageProperties(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)
