package lab.bank.core.extract.infrastructure

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * S3 클라이언트. AWS SDK 그대로이고 주소만 Garage를 가리킨다 → 진짜 S3로 옮기면 endpoint·키만 바뀐다.
 * forcePathStyle: Garage는 http://garage:3900/<버킷>/<키> 경로 방식만 받는다 (가상 호스트 방식은 DNS가 필요)
 * 타임아웃 (CLAUDE.md 7장 외부 호출엔 반드시 타임아웃): 추출 파일은 수 MB라 30초면 넉넉하다. 실패하면 추출이 실패로 끝나고 다시 돌린다
 */
@Configuration
@EnableConfigurationProperties(StorageProperties::class)
@EnableScheduling   // core에서 처음 쓰는 @Scheduled (일자별 추출). 애플리케이션 전체에 한 번이면 된다
class StorageConfig {

    @Bean(destroyMethod = "close")
    fun s3Client(properties: StorageProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKeyId, properties.secretAccessKey)))
            .forcePathStyle(true)
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).build())
            .build()
}
