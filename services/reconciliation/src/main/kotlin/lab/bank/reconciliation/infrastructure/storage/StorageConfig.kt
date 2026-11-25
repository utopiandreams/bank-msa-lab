package lab.bank.reconciliation.infrastructure.storage

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.time.Duration

/**
 * S3 클라이언트. AWS SDK 그대로이고 주소만 Garage를 가리킨다 → 진짜 S3로 옮기면 endpoint·키만 바뀐다.
 * forcePathStyle: Garage는 http://garage:3900/<버킷>/<키> 경로 방식만 받는다 (가상 호스트 방식은 DNS가 필요)
 * Garage 호환 (2026-11-25, GarageS3CompatibilityTest로 재현): SDK v2 기본값은 HTTP에서 본문을 청크마다 서명하고(aws-chunked)
 *   체크섬을 트레일러로 붙이는데, Garage가 이를 "Invalid payload signature"(400)로 거절한다 → 청크 서명을 끄고(본문 전체 해시로 한 번 서명)
 *   체크섬은 필요할 때만 보낸다. 진짜 S3로 옮기면 이 세 줄은 빼도 된다 (S3 호환 저장소마다 SDK 새 기본값을 따라오는 속도가 다르다)
 * 타임아웃 (CLAUDE.md 7장 외부 호출엔 반드시 타임아웃): 추출 파일은 수 MB라 30초면 넉넉하다. 실패하면 그 회차가 실패로 남고 다음 회차에 다시 읽는다
 */
@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class StorageConfig {

    @Bean(destroyMethod = "close")
    fun s3Client(properties: StorageProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKeyId, properties.secretAccessKey)))
            .forcePathStyle(true)
            .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).build())
            .build()
}
