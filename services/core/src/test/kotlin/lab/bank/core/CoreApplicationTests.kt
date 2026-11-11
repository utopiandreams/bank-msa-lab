package lab.bank.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CoreApplicationTests {

	@Test
	fun contextLoads() {
	}

}
