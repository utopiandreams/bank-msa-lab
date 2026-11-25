package lab.bank.transfer.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 결과 불명 해소 설정 (application.yaml의 transfer.resolution.*, ADR-0005).
 * application 패키지에 두는 이유: 유스케이스가 checkAfter·batchSize를 쓴다. infrastructure에 두면 application → infrastructure 의존이 생긴다. 하드코딩하지 않는 이유: 실험 스위치 (CLAUDE.md 5장)
 * 예) 13주 차 패널 "해소 일꾼 멈추기" → PROCESSING·UNKNOWN 체류 시간이 쌓였다가 다시 켜면 풀리는 장면
 *
 * @property enabled      false면 스케줄러 빈을 만들지 않는다 (고객 재요청으로만 풀림)
 * @property pollInterval 한 회차가 끝나고 다음 회차까지 쉬는 시간 (fixedDelay)
 * @property checkAfter   core에 보낸 시각(createdAt) + 이 시간이 지나야 조회한다. **정합성 장치**:
 *                        일찍 조회하면 core가 아직 처리 중인데 "없음"을 보고 실패로 오판한다 (늦은 커밋).
 *                        core 송금 트랜잭션 타임아웃(3초)과 transfer → core 최악 소요(connect 1s + read 3s)보다 길어야 한다
 * @property externalCheckAfter 타행 EXTERNAL_BANK_REQUESTED에 들어간 시각(updatedAt) + 이 시간이 지나야 외부에 조회한다 (8주 차 2부 결정 2).
 *                        **외부 처리 상한**(bank-mock.processing-deadline, 외부와의 계약) + 여유여야 한다. 우리 read-timeout(3초)이 아니다:
 *                        우리가 기다리길 그만둬도 외부는 상한까지 처리할 수 있다. 일찍 물으면 "없음"을 보고 취소 → 늦게 입금 = 이중 지급
 *                        (타행 core 홉 WITHDRAW_REQUESTED·WITHDRAWN·WITHDRAW_CANCEL_REQUESTED는 checkAfter를 쓴다)
 * @property batchSize    한 회차(한 트랜잭션)에 잡는 최대 건수. 조회하는 동안 행 잠금·DB 커넥션을 쥐므로 작게
 */
@ConfigurationProperties(prefix = "transfer.resolution")
data class ResolutionProperties(
    val enabled: Boolean = true,
    val pollInterval: Duration = Duration.ofSeconds(5),
    val checkAfter: Duration = Duration.ofSeconds(5),
    val externalCheckAfter: Duration = Duration.ofSeconds(15),
    val batchSize: Int = 10,
)
