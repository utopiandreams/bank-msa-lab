package lab.bank.reconciliation.infrastructure

import lab.bank.reconciliation.infrastructure.storage.S3ExtractFileReader
import lab.bank.reconciliation.infrastructure.storage.StorageConfig
import lab.bank.reconciliation.infrastructure.storage.StorageProperties
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import kotlin.test.assertContentEquals

/**
 * 진짜 Garage(클러스터와 같은 v2.6.0)에 운영과 같은 S3 클라이언트 설정(StorageConfig)으로 붙는다.
 * 2026-11-25 클러스터에서 "Invalid payload signature"(400)로 올리기가 실패한 것을 여기서 재현한다:
 * AWS SDK v2 기본값(HTTP에서 청크 단위 서명 + 체크섬 트레일러)을 Garage가 받지 않는다 → StorageConfig에서 끈다
 */
@Testcontainers
class GarageS3CompatibilityTest {

    companion object {
        private const val ACCESS_KEY = "GK0123456789abcdef0123456789abcdef"
        private const val SECRET_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private val CONFIG = """
            metadata_dir = "/tmp/meta"
            data_dir = "/tmp/data"
            db_engine = "lmdb"
            replication_factor = 1
            rpc_bind_addr = "[::]:3901"
            rpc_public_addr = "127.0.0.1:3901"
            rpc_secret = "${"0".repeat(64)}"
            [s3_api]
            s3_region = "garage"
            api_bind_addr = "[::]:3900"
            root_domain = ".s3.garage.localhost"
            [admin]
            api_bind_addr = "[::]:3903"
        """.trimIndent()

        @Container
        @JvmStatic
        val garage: GenericContainer<*> = GenericContainer("dxflrs/garage:v2.6.0")
            .withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml")
            .withEnv("GARAGE_DEFAULT_ACCESS_KEY", ACCESS_KEY)
            .withEnv("GARAGE_DEFAULT_SECRET_KEY", SECRET_KEY)
            .withEnv("GARAGE_DEFAULT_BUCKET", "daily-extracts")
            .withCommand("/garage", "server", "--single-node", "--default-bucket")
            .withExposedPorts(3900, 3903)
            .waitingFor(Wait.forHttp("/health").forPort(3903))
    }

    @Test
    fun `운영과 같은 클라이언트 설정으로 Garage에 올리고 읽는다`() {
        val props = StorageProperties("http://${garage.host}:${garage.getMappedPort(3900)}", "garage", "daily-extracts", ACCESS_KEY, SECRET_KEY)
        val s3 = StorageConfig().s3Client(props)
        val content = "transfer_id,kind\n1,DOMESTIC\n".toByteArray()

        s3.putObject(PutObjectRequest.builder().bucket(props.bucket).key("transfer/2026-11-25/x.csv").build(), RequestBody.fromBytes(content))

        assertContentEquals(content, S3ExtractFileReader(s3, props).read("transfer/2026-11-25/x.csv"))
    }
}
