package lab.bank.core.extract.application

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * (transfer의 같은 이름 클래스와 같은 코드. 서비스끼리 코드를 공유하지 않는다: libs/common은 이벤트 스키마만)
 * 추출 파일을 **한 줄씩 디스크에 흘려 쓰면서** 건수·금액 합계·SHA-256을 같이 센다 (정산 파일 trailer 값).
 * 메모리에는 버퍼(수십 KB)만 있다: 건수가 100만이든 1억이든 같다. 체크섬은 쓰는 바이트가 지나가며 계산된다 (DigestOutputStream)
 *
 * 쓰는 법: ExtractFileWriter.open(header).use { w -> rows.forEach { w.append(line, amount) } ; w.finish() }
 */
class ExtractFileWriter private constructor(val file: Path, header: String) : AutoCloseable {

    private val digest = MessageDigest.getInstance("SHA-256")
    private val writer = BufferedWriter(OutputStreamWriter(DigestOutputStream(Files.newOutputStream(file), digest), Charsets.UTF_8), 64 * 1024)
    var rowCount = 0L
        private set
    var amountSum = 0L
        private set
    private var finished = false

    init {
        writer.write(header)
        writer.newLine()
    }

    fun append(line: String, amount: Long) {
        writer.write(line)
        writer.newLine()
        rowCount++
        amountSum += amount
    }

    /** 다 썼다: 버퍼를 비우고 파일을 닫은 뒤 체크섬 (닫기 전에 계산하면 버퍼에 남은 바이트가 빠진다) */
    fun finish(): String {
        writer.close()
        finished = true
        return HexFormat.of().formatHex(digest.digest())
    }

    /** 실패해도 임시 파일을 남기지 않는다 (/tmp는 파드 디스크) */
    override fun close() {
        if (!finished) runCatching { writer.close() }
        Files.deleteIfExists(file)
    }

    companion object {
        fun open(header: String): ExtractFileWriter = ExtractFileWriter(Files.createTempFile("extract-", ".csv"), header)
    }
}
