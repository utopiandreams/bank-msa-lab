# 질문 은행: I/O 모델과 이벤트 루프 (IO)

설명 자료: `docs/learning/io-and-event-loop.md` (✅ = Redisson 4.8.0 jar로 확인)

### IO-001 · 개념 · ★★
**Q.** Redisson의 기본 락(pub/sub)은 이미 누가 쥐고 있을 때 어떻게 기다리나요? Redis를 계속 두드리나요?

<details><summary>답 핵심</summary>

- 획득 Lua가 실패하면 **남은 TTL**을 돌려준다 ✅
- 락 채널(`redisson_lock__channel:{키}`)을 구독하고, 자바 `Semaphore.tryAcquire(남은 TTL)`로 스레드를 멈춘다 ✅ → CPU 0, Redis에 아무것도 안 보냄
- 쥔 쪽이 unlock하면 Lua가 키를 지우고 PUBLISH → Netty 이벤트 루프가 메시지를 받아 `semaphore.release()` ✅ → 대기자 하나가 깨어나 다시 Lua로 시도
- 깨어나도 바로 내 것은 아님 (그 사이 새로 온 요청이 잡았을 수 있음 = 불공정)
- 📎 io-and-event-loop.md 2절
</details>

- 출처: 2026-11-22 ("스레드가 잠잔다는 게 sleep이야? 누가 깨워?")

### IO-002 · 장애 · ★★
**Q.** 락을 쥔 서버가 unlock하기 전에 죽었습니다. pub/sub으로 기다리던 쪽은 영원히 기다리나요?

<details><summary>답 핵심</summary>

- PUBLISH는 영영 오지 않는다
- 하지만 대기 시간을 처음부터 "남은 TTL"로 잡았다 → 키가 만료될 즈음 스스로 깨어나 다시 시도 → 잡는다
- 그래서 획득 Lua가 실패 시 TTL을 돌려준다. 워치독을 쓰면 쥔 쪽이 죽은 뒤 연장이 멈춰 기본 30초 뒤 만료
- 우리 설정은 leaseTime 10초 지정(워치독 꺼짐) → 최대 10초 막힘, 그동안 요청은 대기 상한 1초에 503
- 📎 io-and-event-loop.md 2절, `LockProperties.Redis`
</details>

- 출처: 2026-11-22

### IO-003 · 비교 · ★★
**Q.** `Thread.sleep`, JDBC 응답 대기, 세마포어 대기는 모두 "기다린다"입니다. 무엇이 다른가요? 스핀 락이 느린 이유와 어떻게 연결되나요?

<details><summary>답 핵심</summary>

- 셋 다 기다리는 동안 CPU 0. 차이는 **깨어나는 조건**
- sleep: 시간만 (일이 먼저 끝나도 시간까지 잠) / 블로킹 소켓: 내 소켓에 데이터가 오면 커널이 / 세마포어: 다른 스레드가 release하면
- 스핀 락은 sleep으로 백오프(1ms에서 2배, 최대 128ms ✅) → 락이 풀린 직후에도 남은 sleep을 흘려보내고, 기다리는 동안 계속 Redis에 묻는다
- pub/sub은 풀리자마자 알림으로 깸 → 넘겨받는 시간 짧고 Redis 조용
- 📎 io-and-event-loop.md 1절, 테스트 시간 기준 스핀 2~4배 느림 (k6로 측정 예정)
</details>

- 출처: 2026-11-22

### IO-004 · 개념 · ★★
**Q.** Netty 이벤트 루프는 어떻게 스레드 몇 개로 많은 연결을 다루나요? 이벤트 루프에서 하면 안 되는 일은?

<details><summary>답 핵심</summary>

- 연결(Channel) 하나는 이벤트 루프 스레드 하나에 붙고, 스레드 하나가 여러 소켓을 epoll(리눅스)·kqueue(맥)로 한 번에 걸어 두고 기다린다 (Redisson 기본 NIO, 스레드 32개 ✅)
- 커널이 "이 소켓에 데이터"라고 알려 주면 읽고 해석해서 등록된 콜백 호출 → 다시 대기
- 하면 안 되는 일: **블로킹** (JDBC, sleep, 오래 걸리는 계산). 그 스레드가 맡은 소켓 전부가 멈춘다. 그래서 Redisson 콜백은 release 한 번만 하고 실제 일은 요청 스레드가
- 14주 차 "이벤트 루프 블로킹 재현", gateway도 같은 구조
- 📎 io-and-event-loop.md 3절
</details>

- 출처: 2026-11-22 ("epoll 같은 걸로 여러 소켓 대기하다 콜백?")

### IO-005 · 꼬리 · ★★
**Q.** Redisson·Lettuce는 동기 API(`tryLock()`, `get()`)도 속은 비동기라고 합니다. 어차피 부른 스레드는 기다리는데 이점이 있나요?

<details><summary>답 핵심</summary>

- 부른 스레드 입장에선 이점 없음
- 연결 공유: 블로킹 클라이언트(Jedis)는 응답까지 연결을 독점 → 동시 요청 수만큼 연결 필요, 모자라면 풀 대기 (Hikari 고갈과 같은 병목). 이벤트 루프 클라이언트는 스레드 200개가 연결 몇 개를 같이 씀
- 뒤에서 도는 일에 스레드가 안 듦: 구독, 락 워치독, 재접속, 타임아웃 → 이벤트 루프 타이머
- 엔진 하나로 동기·비동기·리액티브, 가상 스레드와 잘 맞음
- 대가: 스레드 건너가는 비용(μs), 예외 스택이 끊겨 디버깅 어려움
- 📎 io-and-event-loop.md 4·5절
</details>

- 출처: 2026-11-22

### IO-006 · 꼬리 · ★★★
**Q.** 연결 하나로 여러 요청을 동시에 보내면, 돌아오는 응답이 누구 것인지 어떻게 아나요?

<details><summary>답 핵심</summary>

- Redis는 한 연결 안에서 **받은 순서대로** 응답한다
- 클라이언트는 보낸 명령의 Future를 대기열에 순서대로 쌓고, 응답이 오면 맨 앞 Future에 넣는다 → 응답에 "누구 것" 표시가 없어도 순서로 짝을 맞춘다 (파이프라이닝)
- 많아지는 건 연결이 아니라 메모리 안의 대기 항목
- "채널"의 세 뜻 구분: Netty Channel(소켓, 적음) / Redis pub/sub 채널(주제 이름) / 기다리는 요청(Future, 많음)
- 📎 io-and-event-loop.md 6절
</details>

- 출처: 2026-11-22 ("하나의 소켓으로 모든 통신을 할 수 있구나?")

### IO-007 · 장애 · ★★★
**Q.** pub/sub 메시지는 요청 없이 아무 때나 옵니다. 일반 명령과 같은 연결에 섞이면 무슨 문제가 생기고, Redis와 클라이언트는 이걸 어떻게 피하나요?

<details><summary>답 핵심</summary>

- 순서로 짝 맞추기가 깨진다: GET b의 Future에 해제 메시지가 들어감
- RESP2: `SUBSCRIBE`한 연결은 구독 모드 → 구독·구독 해제·PING 등만 허용, 일반 명령은 오류
- 그래서 Redisson은 명령용 연결 풀과 구독용 연결 풀을 나눈다. 구독 연결로 오는 건 주제 이름표로 분배
- RESP3(Redis 6+): push 타입이 생겨 섞여도 구분 가능 (클라이언트 측 캐시 무효화 알림)
- 같은 이유로 `BLPOP`처럼 서버 쪽에서 기다리는 명령도 전용 연결
- 📎 io-and-event-loop.md 7절
</details>

- 출처: 2026-11-22 ("펍섭은 요청 중간에 끼어들 수도 있는 거 아닌가")

### IO-008 · 비교 · ★★
**Q.** Java Redis 클라이언트 Redisson, Lettuce, Jedis의 I/O 방식은 어떻게 다르고, pub/sub을 받을 때 스레드는 어떻게 쓰이나요?

<details><summary>답 핵심</summary>

- Redisson·Lettuce(Spring Data Redis 기본): Netty 이벤트 루프. 메시지가 오면 이벤트 루프가 콜백
- Jedis: 블로킹 소켓. `subscribe()`를 부른 스레드가 연결을 붙잡고 계속 읽으며 멈춤 → 구독마다 스레드 하나
- 같은 "적은 스레드가 많은 연결 + 콜백" 구조: Kafka 클라이언트, WebClient, gateway. 반대편이 Tomcat + JDBC의 thread-per-request
- 📎 io-and-event-loop.md 4절
</details>

- 출처: 2026-11-22
