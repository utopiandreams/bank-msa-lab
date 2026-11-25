package lab.bank.reconciliation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 일일 대사 서비스 (ADR-0006). 각 서비스가 저장소(Garage)에 올린 일자별 추출 파일을 모아
 * transfer ↔ core 원장, 타행 송금 ↔ 외부 은행을 양방향으로 맞춰 보고, 이월·불일치를 기록한다.
 * 별도 서비스인 이유: 검사받는 쪽(transfer)이 검사를 겸하지 않게 (독립성)
 */
@SpringBootApplication
@EnableScheduling   // 대사 일꾼 (infrastructure/worker/ReconWorker)
class ReconciliationApplication

fun main(args: Array<String>) {
    runApplication<ReconciliationApplication>(*args)
}
