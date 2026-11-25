package lab.bank.reconciliation.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/** 추출 파일 저장소 (Garage, S3 호환) 접속 정보 (application.yaml의 reconciliation.storage.*, ADR-0006) */
@ConfigurationProperties(prefix = "reconciliation.storage")
data class StorageProperties(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)
