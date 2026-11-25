package lab.bank.reconciliation.application

/** 파일 불량: 없음·체크섬·건수·합계가 알림과 다름, 형식이 깨짐. 대사 불일치가 아니라 회차 실패 (결정 12) */
class ExtractFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
