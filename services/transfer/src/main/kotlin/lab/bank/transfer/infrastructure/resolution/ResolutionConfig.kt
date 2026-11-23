package lab.bank.transfer.infrastructure.resolution

import lab.bank.transfer.application.ResolutionProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

// @EnableScheduling은 OutboxConfig에 이미 있다 (애플리케이션 전체에 한 번이면 된다)
@Configuration
@EnableConfigurationProperties(ResolutionProperties::class)
class ResolutionConfig
