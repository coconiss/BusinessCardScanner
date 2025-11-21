package com.coconiss.businesscardscanner.ocr

import android.util.Log
import com.coconiss.businesscardscanner.data.Contact
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

class ContactParser {

    private val TAG = "ContactParser"

    // 개선된 전화번호 패턴 (국제번호, 괄호, 다양한 구분자)
    private val phonePattern = Pattern.compile(
        "(?:\\+?82[-\\s]?)?(?:\\(?0\\d{1,2}\\)?|01[016789])[-\\s]?\\d{3,4}[-\\s]?\\d{4}"
    )

    // 개선된 이메일 패턴
    private val emailPattern = Pattern.compile(
        "[a-zA-Z0-9][a-zA-Z0-9._%+-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}"
    )

    // URL 패턴 (이메일과 혼동 방지)
    private val urlPattern = Pattern.compile(
        "(?:https?://)?(?:www\\.)?[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}"
    )

    private val companyKeywords = listOf(
        "주식회사", "(주)", "Co.", "Ltd.", "Inc.",
        "Corporation", "Corp.", "Company", "그룹", "Group",
        "유한회사", "㈜", "법인", "상사", "기업",
        "연구소", "센터", "재단"
    )

    private val positionKeywords = listOf(
        "회장", "부회장", "사장", "부사장", "대표이사", "전무이사", "상무이사", "이사", "감사",
        "대표", "전무", "상무", "본부장", "센터장", "실장", "지사장", "공장장",
        "팀장", "부장", "차장", "과장", "대리", "주임", "사원", "연구원",
        "선임연구원", "책임연구원", "수석연구원", "파트장", "그룹장",
        "컨설턴트", "디자이너", "개발자", "엔지니어", "아키텍트", "매니저",
        "CEO", "CTO", "CFO", "COO", "CMO", "CIO", "CSO", "CPO",
        "VP", "President", "Director", "Manager", "Leader",
        "Developer", "Designer", "Engineer", "Consultant", "Chief"
    )

    private val addressKeywords = listOf(
        "시", "구", "동", "로", "길", "가", "번지", "층", "호", "빌딩",
        "Street", "St.", "Ave", "Avenue", "Road", "Rd.",
        "Floor", "Fl.", "Building", "Bldg"
    )

    private val departmentKeywords = listOf(
        "경영", "기획", "전략", "인사", "총무", "재무", "회계", "법무", "홍보", "IR",
        "개발", "연구", "디자인", "기술", "생산", "품질", "QA", "QC",
        "영업", "마케팅", "사업", "고객", "서비스", "해외", "국내",
        "본부", "사업부", "센터", "실", "팀", "파트", "그룹", "솔루션", "컨설팅",
        "R&D", "HR", "GA", "부문", "Division"
    )

    private val companyDescriptionKeywords = listOf(
        "코스닥상장법인", "벤처기업", "이노비즈", "메인비즈", "상장", "인증"
    )

    // 한글 이름 패턴: 2~4자
    private val koreanNamePattern = Pattern.compile("^[가-힣]{2,4}$")

    // 영문 이름 패턴
    private val englishNamePattern = Pattern.compile("^[A-Z][a-z]+(?:\\s[A-Z][a-z]+)*$")

    // 한국의 흔한 성씨 목록
    private val koreanSurnames = listOf(
        "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안",
        "송", "류", "전", "홍", "고", "문", "양", "손", "배", "조", "백", "허", "유", "남", "심", "노",
        "하", "곽", "성", "차", "도", "구", "우", "주", "라", "전", "민", "진", "지", "엄", "채",
        "원", "천", "방", "공", "현", "함", "변", "염", "여", "추", "도", "소", "석", "선", "설", "마", "길",
        "연", "위", "표", "명", "기", "반", "왕", "금", "옥", "육", "인", "맹", "제", "모", "탁", "국", "어",
        "은", "편", "용", "예", "경", "봉"
    )

    /**
     * OCR 오인식 보정 함수 (개선)
     */
    private fun correctOCRErrors(text: String): String {
        var corrected = text

        // 이메일 보정
        if (corrected.contains("@")) {
            // 쉼표를 점으로
            corrected = corrected.replace(",", ".")
            // @앞뒤 공백 제거
            corrected = corrected.replace(Regex("\\s*@\\s*"), "@")
            // 점 앞뒤 공백 제거
            corrected = corrected.replace(Regex("\\s*\\.\\s*"), ".")
        }

        // 전화번호 보정
        if (corrected.contains(Regex("\\d{2,}"))) {
            // O/o를 0으로 (숫자 문맥에서)
            corrected = corrected.replace(Regex("(?<=[0-9])[Oo](?=[0-9])"), "0")
            corrected = corrected.replace(Regex("^[Oo](?=[0-9])"), "0")
            corrected = corrected.replace(Regex("(?<=[0-9])[Oo]$"), "0")

            // l/I를 1로 (숫자 문맥에서)
            corrected = corrected.replace(Regex("(?<=[0-9])[lI](?=[0-9])"), "1")
            corrected = corrected.replace(Regex("^[lI](?=[0-9])"), "1")
            corrected = corrected.replace(Regex("(?<=[0-9])[lI]$"), "1")
        }

        // 불필요한 공백 정리
        corrected = corrected.replace(Regex("\\s+"), " ").trim()

        return corrected
    }

    /**
     * 전화번호 정규화 (개선)
     */
    private fun normalizePhoneNumber(phone: String): String {
        // 숫자와 + 기호만 남기기
        var normalized = phone.replace(Regex("[^0-9+]"), "")

        // 국제번호 처리
        if (normalized.startsWith("+82")) {
            normalized = "0" + normalized.substring(3)
        } else if (normalized.startsWith("82") && normalized.length > 10) {
            normalized = "0" + normalized.substring(2)
        }

        // 하이픈 추가
        normalized = when {
            // 휴대폰 (010, 011, 016, 017, 018, 019)
            normalized.startsWith("01") && normalized.length == 11 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 7)}-${normalized.substring(7)}"
            }
            normalized.startsWith("01") && normalized.length == 10 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6)}"
            }
            // 서울 (02)
            normalized.startsWith("02") && normalized.length == 10 -> {
                "${normalized.substring(0, 2)}-${normalized.substring(2, 6)}-${normalized.substring(6)}"
            }
            normalized.startsWith("02") && normalized.length == 9 -> {
                "${normalized.substring(0, 2)}-${normalized.substring(2, 5)}-${normalized.substring(5)}"
            }
            // 기타 지역번호 (031, 051 등)
            normalized.startsWith("0") && normalized.length == 11 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 7)}-${normalized.substring(7)}"
            }
            normalized.startsWith("0") && normalized.length == 10 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6)}"
            }
            else -> normalized
        }

        return normalized
    }

    /**
     * 신뢰도 기반 라인 분류
     */
    private fun classifyLine(line: String): Pair<String, Int> {
        var maxScore = 0
        var category = "unknown"

        // 이름 확인
        val nameScore = if (koreanNamePattern.matcher(line).matches() &&
            koreanSurnames.any { line.startsWith(it) }) {
            40
        } else if (englishNamePattern.matcher(line).matches()) {
            35
        } else 0

        // 회사명 확인
        val companyScore = if (companyKeywords.any { line.contains(it) }) {
            val hasDescription = companyDescriptionKeywords.any { line.contains(it) }
            if (hasDescription) 10 else 35
        } else 0

        // 주소 확인
        val addressScore = addressKeywords.count { line.contains(it) } * 15 +
                if (line.length > 15) 10 else 0

        // 직책 확인
        val positionScore = if (positionKeywords.any { line.contains(it) }) 30 else 0

        // 최고 점수 카테고리 선택
        listOf(
            "name" to nameScore,
            "company" to companyScore,
            "address" to addressScore,
            "position" to positionScore
        ).forEach { (cat, score) ->
            if (score > maxScore) {
                maxScore = score
                category = cat
            }
        }

        return category to maxScore
    }

    fun parseContact(text: Text): Contact {
        val contact = Contact()

        // 모든 텍스트 블록을 라인별로 수집하고 신뢰도로 정렬
        val allBlocks = text.textBlocks
            .flatMap { block ->
                block.lines.map { line ->
                    Triple(
                        correctOCRErrors(line.text.trim()),
                        line.confidence ?: 0.5f,
                        line.boundingBox
                    )
                }
            }
            .filter { it.first.isNotBlank() }
            .sortedByDescending { it.second } // 신뢰도 높은 순

        Log.d(TAG, "--- Starting Contact Parsing ---")
        Log.d(TAG, "Total lines: ${allBlocks.size}")
        allBlocks.forEachIndexed { idx, (line, conf, _) ->
            Log.d(TAG, "Line $idx (conf: $conf): $line")
        }

        val processedLines = mutableSetOf<String>()
        val allPhoneNumbers = mutableListOf<String>()
        val allEmails = mutableListOf<String>()

        // 1단계: 전화번호 추출 (모든 후보)
        Log.d(TAG, "[Phase 1] Extracting Phone Numbers...")
        allBlocks.forEach { (line, _, _) ->
            val phoneMatcher = phonePattern.matcher(line)
            while (phoneMatcher.find()) {
                val rawPhone = phoneMatcher.group()
                val normalizedPhone = normalizePhoneNumber(rawPhone)
                Log.d(TAG, "Found phone: '$rawPhone' -> '$normalizedPhone'")
                if (normalizedPhone.length >= 9) {
                    allPhoneNumbers.add(normalizedPhone)
                    processedLines.add(line)
                }
            }
        }

        // 휴대폰 번호 우선, 없으면 첫 번째
        contact.phoneNumber = allPhoneNumbers.firstOrNull { it.startsWith("010") }
            ?: allPhoneNumbers.firstOrNull()
                    ?: ""
        Log.d(TAG, "Selected phone: '${contact.phoneNumber}'")

        // 2단계: 이메일 추출
        Log.d(TAG, "[Phase 2] Extracting Email...")
        allBlocks.forEach { (line, _, _) ->
            if (!processedLines.contains(line)) {
                val emailMatcher = emailPattern.matcher(line)
                while (emailMatcher.find()) {
                    val email = emailMatcher.group()
                    // URL이 아닌지 확인
                    if (!urlPattern.matcher(email).matches()) {
                        allEmails.add(email)
                        processedLines.add(line)
                        Log.d(TAG, "Found email: '$email'")
                    }
                }
            }
        }
        contact.email = allEmails.firstOrNull() ?: ""

        // 3단계: 복합 라인 분석 (이름 + 직책)
        Log.d(TAG, "[Phase 3] Analyzing complex lines...")
        val remainingLines = allBlocks
            .filter { !processedLines.contains(it.first) }
            .toMutableList()

        // 직책 키워드 포함 라인 찾기
        val positionLine = remainingLines.firstOrNull { (line, _, _) ->
            positionKeywords.any { line.contains(it) }
        }

        if (positionLine != null) {
            val (line, _, _) = positionLine
            Log.d(TAG, "Found position line: '$line'")

            val words = line.split(Regex("[\\s/|:,.]+")).filter { it.isNotBlank() }
            val nameCandidates = mutableListOf<String>()

            for (word in words) {
                when {
                    positionKeywords.contains(word) -> {
                        if (contact.position.isEmpty()) {
                            contact.position = word
                        }
                    }
                    departmentKeywords.any { word.contains(it) } -> {
                        // 부서는 제외
                    }
                    koreanNamePattern.matcher(word).matches() -> {
                        nameCandidates.add(word)
                    }
                }
            }

            // 이름 선택 (성씨 있는 것 우선)
            contact.name = nameCandidates.firstOrNull { name ->
                koreanSurnames.any { name.startsWith(it) }
            } ?: nameCandidates.firstOrNull() ?: ""

            remainingLines.remove(positionLine)
        }

        // 4단계: 회사명 추출
        Log.d(TAG, "[Phase 4] Extracting Company...")
        val companyLines = remainingLines
            .filter { (line, _, _) -> companyKeywords.any { line.contains(it) } }
            .sortedByDescending { (line, conf, _) ->
                val (_, score) = classifyLine(line)
                score * conf
            }

        if (companyLines.isNotEmpty()) {
            contact.company = companyLines.first().first
            remainingLines.remove(companyLines.first())
            Log.d(TAG, "Selected company: '${contact.company}'")
        }

        // 5단계: 주소 추출
        Log.d(TAG, "[Phase 5] Extracting Address...")
        val addressLines = remainingLines
            .filter { (line, _, _) ->
                addressKeywords.any { line.contains(it) } && line.length > 8
            }
            .sortedByDescending { (line, conf, _) ->
                val keywordCount = addressKeywords.count { line.contains(it) }
                keywordCount * 10 + line.length + conf * 10
            }

        if (addressLines.isNotEmpty()) {
            contact.address = addressLines.first().first
            remainingLines.remove(addressLines.first())
            Log.d(TAG, "Selected address: '${contact.address}'")
        }

        // 6단계: 이름이 없으면 추출
        if (contact.name.isEmpty()) {
            Log.d(TAG, "[Phase 6] Extracting Name from remaining lines...")
            val nameLines = remainingLines
                .filter { (line, _, _) ->
                    koreanNamePattern.matcher(line).matches() &&
                            !departmentKeywords.any { line.contains(it) }
                }
                .sortedByDescending { (line, conf, _) ->
                    val hasCommonSurname = koreanSurnames.any { line.startsWith(it) }
                    (if (hasCommonSurname) 20 else 0) + conf * 10
                }

            contact.name = nameLines.firstOrNull()?.first ?: ""
            if (contact.name.isNotEmpty()) {
                Log.d(TAG, "Selected name: '${contact.name}'")
            }
        }

        // 7단계: 직책이 없으면 추출
        if (contact.position.isEmpty()) {
            remainingLines.forEach { (line, _, _) ->
                val position = positionKeywords.firstOrNull { line.contains(it) }
                if (position != null) {
                    contact.position = position
                    Log.d(TAG, "Selected position: '${contact.position}'")
                    return@forEach
                }
            }
        }

        Log.d(TAG, "--- Finished Contact Parsing ---")
        Log.d(TAG, "Final Contact: $contact")

        return contact
    }
}