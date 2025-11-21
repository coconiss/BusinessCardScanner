package com.coconiss.businesscardscanner.ocr

import android.util.Log
import com.coconiss.businesscardscanner.data.Contact
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

class ContactParser {

    private val TAG = "ContactParser"

    // 전화번호 라벨 패턴
    private val phoneLabelPattern = Pattern.compile(
        "(?i)^\\s*(Mobile|Tel|HP|Phone|전화|휴대폰|핸드폰|연락처|M|T|P|H|FAX|Fax|팩스|F)\\s*[.:\\-]?\\s*",
        Pattern.CASE_INSENSITIVE
    )

    // 이메일 라벨 패턴
    private val emailLabelPattern = Pattern.compile(
        "(?i)^\\s*(E-?mail|Email|메일|이메일|E)\\s*[.:\\-]?\\s*",
        Pattern.CASE_INSENSITIVE
    )

    // 개선된 전화번호 패턴 (국제번호, 괄호, 다양한 구분자: -, ., 공백)
    private val phonePattern = Pattern.compile(
        "(?:\\+?82[-.\\s]?)?(?:\\(?0?\\d{1,2}\\)?|01[016789])[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}"
    )

    // +82 국제번호 패턴 (별도 처리)
    private val internationalPhonePattern = Pattern.compile(
        "\\+82[-.\\s]?(?:10|\\d{1,2})[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}"
    )

    // 개선된 이메일 패턴
    private val emailPattern = Pattern.compile(
        "[a-zA-Z0-9][a-zA-Z0-9._%+-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}"
    )

    // URL 패턴 (이메일과 혼동 방지)
    private val urlPattern = Pattern.compile(
        "(?:https?://)?(?:www\\.)?[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}"
    )

    // 띄어쓰기된 한글 이름 패턴 (예: "홍 길 동", "김 철 수")
    private val spacedKoreanNamePattern = Pattern.compile(
        "^[가-힣]\\s+[가-힣](?:\\s+[가-힣])?$"
    )

    private val companyKeywords = listOf(
        "주식회사", "(주)", "Co.", "Ltd.", "Inc.",
        "Corporation", "Corp.", "Company", "그룹", "Group",
        "유한회사", "㈜", "법인", "상사",
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
        "R&D", "HR", "GA", "부문", "Division",
        "생산관리", "품질관리", "경영관리", "인사관리", "자재관리"
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
        "송", "류", "전", "홍", "고", "문", "양", "손", "배", "백", "허", "유", "남", "심", "노",
        "하", "곽", "성", "차", "도", "구", "우", "주", "라", "민", "진", "지", "엄", "채",
        "원", "천", "방", "공", "현", "함", "변", "염", "여", "추", "소", "석", "선", "설", "마", "길",
        "연", "위", "표", "명", "기", "반", "왕", "금", "옥", "육", "인", "맹", "제", "모", "탁", "국", "어",
        "은", "편", "용", "예", "경", "봉"
    )

    /**
     * 라인 전처리 - 라벨 제거 및 정규화
     */
    private fun preprocessLine(line: String): String {
        var processed = line.trim()
        processed = emailLabelPattern.matcher(processed).replaceFirst("")
        processed = phoneLabelPattern.matcher(processed).replaceFirst("")
        return processed.trim()
    }

    /**
     * 이메일 추출 (라벨 포함된 경우 처리)
     */
    private fun extractEmail(line: String): String? {
        val processed = emailLabelPattern.matcher(line).replaceFirst("").trim()

        val matcher = emailPattern.matcher(processed)
        if (matcher.find()) {
            val email = matcher.group()
            if (!urlPattern.matcher(email).matches()) {
                return email
            }
        }

        val originalMatcher = emailPattern.matcher(line)
        if (originalMatcher.find()) {
            val email = originalMatcher.group()
            if (!urlPattern.matcher(email).matches()) {
                return email
            }
        }

        return null
    }

    /**
     * 전화번호 추출 (라벨 포함된 경우 처리)
     */
    private fun extractPhone(line: String): String? {
        val processed = phoneLabelPattern.matcher(line).replaceFirst("").trim()

        // +82 국제번호 패턴 먼저 시도
        val intlMatcher = internationalPhonePattern.matcher(processed)
        if (intlMatcher.find()) {
            return normalizePhoneNumber(intlMatcher.group())
        }

        // 일반 전화번호 패턴 매칭
        val matcher = phonePattern.matcher(processed)
        if (matcher.find()) {
            return normalizePhoneNumber(matcher.group())
        }

        // 원본에서도 시도
        val originalIntlMatcher = internationalPhonePattern.matcher(line)
        if (originalIntlMatcher.find()) {
            return normalizePhoneNumber(originalIntlMatcher.group())
        }

        val originalMatcher = phonePattern.matcher(line)
        if (originalMatcher.find()) {
            return normalizePhoneNumber(originalMatcher.group())
        }

        return null
    }

    /**
     * 띄어쓰기된 이름 정규화 (예: "홍 길 동" → "홍길동")
     */
    private fun normalizeSpacedName(text: String): String {
        if (spacedKoreanNamePattern.matcher(text).matches()) {
            return text.replace(" ", "")
        }
        return text
    }

    /**
     * 복합 라인에서 이름 추출 (예: "생산관리팀 / 주임 홍 길 동" 또는 "홍길동 주임")
     */
    private fun extractNameFromComplexLine(line: String): String? {
        Log.d(TAG, "복합 라인에서 이름 추출 시도: '$line'")

        val parts = line.split(Regex("[/|]")).map { it.trim() }
        val nameCandidates = mutableListOf<Pair<String, Int>>()

        for (part in parts) {
            // 1. 직책 키워드 앞뒤에서 이름 찾기
            for (position in positionKeywords) {
                if (part.contains(position)) {
                    // 직책 뒤의 텍스트 추출
                    val afterPosition = part.substringAfter(position).trim()
                    val nameAfter = findKoreanName(afterPosition)
                    if (nameAfter != null) {
                        nameCandidates.add(nameAfter to 100)
                        Log.d(TAG, "직책 뒤에서 이름 발견: '$nameAfter'")
                    }

                    // 직책 앞의 텍스트 추출
                    val beforePosition = part.substringBefore(position).trim()
                    val nameBefore = findKoreanName(beforePosition)
                    if (nameBefore != null) {
                        nameCandidates.add(nameBefore to 100)
                        Log.d(TAG, "직책 앞에서 이름 발견: '$nameBefore'")
                    }
                }
            }

            // 2. 부서 키워드가 없는 파트에서 이름 찾기
            val hasDepartment = departmentKeywords.any { part.contains(it) }
            if (!hasDepartment) {
                val nameInPart = findKoreanName(part)
                if (nameInPart != null) {
                    val hasPosition = positionKeywords.any { part.contains(it) }
                    val score = if (hasPosition) 80 else 60
                    nameCandidates.add(nameInPart to score)
                    Log.d(TAG, "파트에서 이름 발견: '$nameInPart' (점수: $score)")
                }
            }
        }

        // 3. 전체 라인에서 이름 패턴 찾기 (부서/직책 제외)
        val lineWithoutDeptPosition = removeNonNameParts(line)
        val nameInLine = findKoreanName(lineWithoutDeptPosition)
        if (nameInLine != null && nameCandidates.none { it.first == nameInLine }) {
            nameCandidates.add(nameInLine to 50)
            Log.d(TAG, "전체 라인에서 이름 발견: '$nameInLine'")
        }

        return nameCandidates.maxByOrNull { it.second }?.first
    }

    /**
     * 텍스트에서 한글 이름 찾기
     */
    private fun findKoreanName(text: String): String? {
        if (text.isBlank()) return null

        // 1. 띄어쓰기된 이름 패턴 확인 (예: "홍 길 동")
        val spacedPattern = Pattern.compile("[가-힣](?:\\s+[가-힣]){1,3}")
        val spacedMatcher = spacedPattern.matcher(text)
        while (spacedMatcher.find()) {
            val potentialName = spacedMatcher.group().replace("\\s+".toRegex(), "")
            if (potentialName.length in 2..4 && koreanSurnames.any { potentialName.startsWith(it) }) {
                return potentialName
            }
        }

        // 2. 일반 이름 패턴 확인 (예: "홍길동")
        val normalPattern = Pattern.compile("[가-힣]{2,4}")
        val normalMatcher = normalPattern.matcher(text)
        while (normalMatcher.find()) {
            val potentialName = normalMatcher.group()
            val isDepartment = departmentKeywords.any { potentialName.contains(it) || it.contains(potentialName) }
            val isPosition = positionKeywords.any { potentialName == it }

            if (!isDepartment && !isPosition && koreanSurnames.any { potentialName.startsWith(it) }) {
                return potentialName
            }
        }

        // 3. 전체 텍스트가 이름인 경우
        val trimmed = text.trim()
        val normalized = normalizeSpacedName(trimmed)
        if (koreanNamePattern.matcher(normalized).matches() &&
            koreanSurnames.any { normalized.startsWith(it) }) {
            return normalized
        }

        return null
    }

    /**
     * 부서/직책 키워드 제거
     */
    private fun removeNonNameParts(text: String): String {
        var result = text

        for (dept in departmentKeywords) {
            result = result.replace(dept, " ")
        }

        for (pos in positionKeywords) {
            result = result.replace(pos, " ")
        }

        result = result.replace(Regex("[/|·•]"), " ")

        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 직책 추출 (복합 라인에서)
     */
    private fun extractPositionFromLine(line: String): String? {
        for (position in positionKeywords) {
            if (line.contains(position)) {
                return position
            }
        }
        return null
    }

    /**
     * OCR 오인식 보정 함수
     */
    private fun correctOCRErrors(text: String): String {
        var corrected = text

        if (corrected.contains("@") || corrected.lowercase().contains("mail")) {
            corrected = corrected.replace(",", ".")
            corrected = corrected.replace(Regex("\\s*@\\s*"), "@")
            corrected = corrected.replace(Regex("\\s*\\.\\s*(?=[a-zA-Z])"), ".")
        }

        if (corrected.contains(Regex("\\d{2,}"))) {
            corrected = corrected.replace(Regex("(?<=[0-9])[Oo](?=[0-9])"), "0")
            corrected = corrected.replace(Regex("^[Oo](?=[0-9])"), "0")
            corrected = corrected.replace(Regex("(?<=[0-9])[Oo]$"), "0")
            corrected = corrected.replace(Regex("(?<=[0-9])[lI](?=[0-9])"), "1")
            corrected = corrected.replace(Regex("^[lI](?=[0-9])"), "1")
            corrected = corrected.replace(Regex("(?<=[0-9])[lI]$"), "1")
        }

        corrected = corrected.replace(Regex("\\s{2,}"), " ").trim()

        return corrected
    }

    /**
     * 전화번호 정규화
     */
    private fun normalizePhoneNumber(phone: String): String {
        var normalized = phone.replace(Regex("[^0-9+]"), "")

        if (normalized.startsWith("+82")) {
            normalized = "0" + normalized.substring(3)
        } else if (normalized.startsWith("82") && normalized.length > 10) {
            normalized = "0" + normalized.substring(2)
        }

        if (!normalized.startsWith("0") && normalized.length >= 9) {
            normalized = "0$normalized"
        }

        normalized = when {
            normalized.startsWith("01") && normalized.length == 11 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 7)}-${normalized.substring(7)}"
            }
            normalized.startsWith("01") && normalized.length == 10 -> {
                "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6)}"
            }
            normalized.startsWith("02") && normalized.length == 10 -> {
                "${normalized.substring(0, 2)}-${normalized.substring(2, 6)}-${normalized.substring(6)}"
            }
            normalized.startsWith("02") && normalized.length == 9 -> {
                "${normalized.substring(0, 2)}-${normalized.substring(2, 5)}-${normalized.substring(5)}"
            }
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

        val nameScore = if (koreanNamePattern.matcher(line).matches() &&
            koreanSurnames.any { line.startsWith(it) }) {
            40
        } else if (englishNamePattern.matcher(line).matches()) {
            35
        } else 0

        val companyScore = if (companyKeywords.any { line.contains(it) }) {
            val hasDescription = companyDescriptionKeywords.any { line.contains(it) }
            if (hasDescription) 10 else 35
        } else 0

        val addressScore = addressKeywords.count { line.contains(it) } * 15 +
                if (line.length > 15) 10 else 0

        val positionScore = if (positionKeywords.any { line.contains(it) }) 30 else 0

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
            .sortedByDescending { it.second }

        Log.d(TAG, "--- Starting Contact Parsing ---")
        Log.d(TAG, "Total lines: ${allBlocks.size}")
        allBlocks.forEachIndexed { idx, (line, conf, _) ->
            Log.d(TAG, "Line $idx (conf: $conf): $line")
        }

        val processedLines = mutableSetOf<String>()
        val allPhoneNumbers = mutableListOf<String>()
        val allEmails = mutableListOf<String>()

        // 1단계: 전화번호 추출
        Log.d(TAG, "[Phase 1] Extracting Phone Numbers...")
        allBlocks.forEach { (line, _, _) ->
            val phone = extractPhone(line)
            if (phone != null && phone.length >= 9) {
                Log.d(TAG, "Found phone: '$phone' from line: '$line'")
                allPhoneNumbers.add(phone)
                processedLines.add(line)
            }
        }

        contact.phoneNumber = allPhoneNumbers.firstOrNull { it.startsWith("010") }
            ?: allPhoneNumbers.firstOrNull()
                    ?: ""
        Log.d(TAG, "Selected phone: '${contact.phoneNumber}'")

        // 2단계: 이메일 추출
        Log.d(TAG, "[Phase 2] Extracting Email...")
        allBlocks.forEach { (line, _, _) ->
            if (!processedLines.contains(line)) {
                val email = extractEmail(line)
                if (email != null) {
                    allEmails.add(email)
                    processedLines.add(line)
                    Log.d(TAG, "Found email: '$email' from line: '$line'")
                }
            }
        }
        contact.email = allEmails.firstOrNull() ?: ""

        // 3단계: 복합 라인 분석
        Log.d(TAG, "[Phase 3] Analyzing complex lines...")
        val remainingLines = allBlocks
            .filter { !processedLines.contains(it.first) }
            .toMutableList()

        val positionLine = remainingLines.firstOrNull { (line, _, _) ->
            positionKeywords.any { line.contains(it) }
        }

        if (positionLine != null) {
            val (line, _, _) = positionLine
            Log.d(TAG, "Found position line: '$line'")

            val extractedName = extractNameFromComplexLine(line)
            if (extractedName != null) {
                contact.name = extractedName
                Log.d(TAG, "Extracted name from complex line: '${contact.name}'")
            }

            val extractedPosition = extractPositionFromLine(line)
            if (extractedPosition != null) {
                contact.position = extractedPosition
                Log.d(TAG, "Extracted position: '${contact.position}'")
            }

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

        // 6단계: 이름이 없으면 다양한 방법으로 추출
        if (contact.name.isEmpty()) {
            Log.d(TAG, "[Phase 6] Extracting Name from remaining lines...")

            val allNameCandidates = mutableListOf<Pair<String, Int>>()

            for ((line, conf, _) in remainingLines) {
                val extractedName = extractNameFromComplexLine(line)
                if (extractedName != null) {
                    val score = (conf * 100).toInt()
                    allNameCandidates.add(extractedName to score)
                    Log.d(TAG, "후보 이름 발견: '$extractedName' (점수: $score, 라인: '$line')")
                }

                val normalized = normalizeSpacedName(line)
                if (koreanNamePattern.matcher(normalized).matches()) {
                    val isDepartment = departmentKeywords.any { line.contains(it) }
                    val isPosition = positionKeywords.any { normalized == it }

                    if (!isDepartment && !isPosition && koreanSurnames.any { normalized.startsWith(it) }) {
                        val score = (conf * 120).toInt()
                        allNameCandidates.add(normalized to score)
                        Log.d(TAG, "단독 이름 라인 발견: '$normalized' (점수: $score)")
                    }
                }
            }

            val bestName = allNameCandidates.maxByOrNull { it.second }
            if (bestName != null) {
                contact.name = bestName.first
                Log.d(TAG, "최종 선택된 이름: '${contact.name}'")
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