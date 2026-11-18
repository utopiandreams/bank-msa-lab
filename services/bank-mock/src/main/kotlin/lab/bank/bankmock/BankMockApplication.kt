package lab.bank.bankmock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BankMockApplication

fun main(args: Array<String>) {
    runApplication<BankMockApplication>(*args)
}
