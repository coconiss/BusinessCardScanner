package com.coconiss.businesscardscanner.ocr

import android.util.Log
import com.coconiss.businesscardscanner.data.Contact
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

class ContactParser {

    private val TAG = "ContactParser"

    private val phonePattern = Pattern.compile(
        "(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{2,4}\\)?[\\d-.\\s]{7,10}"
    )

    private val emailPattern = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    )

    private val companyKeywords = listOf(
        "주식회사", "(주)", "Co.", "Ltd.", "Inc.",
        "Corporation", "Corp.", "Company", "그룹", "Group",
        "유한회사", "㈜", "법인"
    )

    private val positionKeywords = listOf(
        "회장", "부회장", "사장", "부사장", "대표이사", "전무이사", "상무이사", "이사", "감사",
        "대표", "전무", "상무", "본부장", "센터장", "실장", "지사장", "공장장",
        "팀장", "부장", "차장", "과장", "대리", "주임", "사원", "연구원",
        "선임연구원", "책임연구원", "수석연구원", "파트장", "그룹장",
        "컨설턴트", "디자이너", "개발자", "엔지니어", "아키텍트",
        "CEO", "CTO", "CFO", "COO", "CMO", "CIO", "CSO", "CPO",
        "VP", "President", "Director", "Manager", "Leader",
        "Developer", "Designer", "Engineer", "Consultant", "Chief"
    )

    private val addressKeywords = listOf(
        "시", "구", "동", "로", "길", "가", "번지", "층", "호",
        "Street", "St.", "Ave", "Avenue", "Road", "Rd.", "Floor", "Fl."
    )

    private val departmentKeywords = listOf(
        "경영", "기획", "전략", "인사", "총무", "재무", "회계", "법무", "홍보", "IR",
        "개발", "연구", "디자인", "기술", "생산", "품질", "QA", "QC",
        "영업", "마케팅", "사업", "고객", "서비스", "해외", "국내",
        "본부", "사업부", "센터", "실", "팀", "파트", "그룹", "솔루션", "컨설팅",
        "R&D", "HR", "GA"
    )

    private val companyDescriptionKeywords = listOf(
        "코스닥상장법인", "벤처기업", "이노비즈", "메인비즈"
    )

    // 한글 이름 패턴: 2~4자
    private val koreanNamePattern = Pattern.compile("^[가-힣]{2,4}$")

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
     * OCR 오인식 보정 함수
     */
    private fun correctOCRErrors(text: String): String {
        var corrected = text

        // 이메일에서 쉼표를 점으로 변환
        if (corrected.contains("@")) {
            val emailPart = corrected.substringAfter("@")
            if (emailPart.contains(",")) {
                corrected = corrected.replace(",", ".")
            }
        }

        // 숫자 0과 알파벳 O/o, 숫자 1과 알파벳 l 혼동 보정
        if (corrected.contains(Regex("[0-9]"))) {
            // 이메일 문맥이 아닐 경우에만 O/o -> 0 변환을 수행 (숫자 우선)
            // 이메일에서는 알파벳을 우선하므로 이 변환을 건너뛴다.
            if (!corrected.contains("@")) {
                corrected = corrected.replace(Regex("(?<=\\d)[Oo]"), "0") // 숫자 뒤 O/o
                corrected = corrected.replace(Regex("[Oo](?=\\d)"), "0") // 숫자 앞 O/o
            }
            // l -> 1 변환은 전화번호 등에서 유용하므로 항상 적용
            corrected = corrected.replace(Regex("(?<=\\d)l"), "1")     // 숫자 뒤 l
            corrected = corrected.replace(Regex("l(?=\\d)"), "1")     // 숫자 앞 l
        }

        // 불필요한 공백 제거
        corrected = corrected.replace(Regex("\\s+"), " ").trim()

        return corrected
    }

    /**
     * 전화번호 정규화
     */
    private fun normalizePhoneNumber(phone: String): String {
        var normalized = phone.replace(Regex("[^0-9+]"), "")

        // 국제번호 처리
        if (normalized.startsWith("+82")) {
            normalized = "0" + normalized.substring(3)
        } else if (normalized.startsWith("82") && normalized.length > 10) {
            normalized = "0" + normalized.substring(2)
        }

        // 하이픈 추가 (010-xxxx-xxxx 형식)
        if (normalized.startsWith("010") && normalized.length == 11) {
            normalized = "${normalized.substring(0, 3)}-${normalized.substring(3, 7)}-${normalized.substring(7)}"
        } else if (normalized.length == 10 && normalized.startsWith("0")) {
            // 지역번호 (02, 031 등)
            if (normalized.startsWith("02")) {
                normalized = "${normalized.substring(0, 2)}-${normalized.substring(2, 6)}-${normalized.substring(6)}"
            } else {
                normalized = "${normalized.substring(0, 3)}-${normalized.substring(3, 6)}-${normalized.substring(6)}"
            }
        }

        return normalized
    }

    /**
     * 직책 추출
     */
    private fun extractPosition(line: String): String? {
        return positionKeywords.firstOrNull { keyword ->
            line.contains(keyword)
        }
    }

    /**
     * 라인 신뢰도 점수 계산
     */
    private fun calculateLineConfidence(line: String, category: String): Int {
        var score = 0

        when (category) {
            "name" -> {
                if (koreanNamePattern.matcher(line).matches()) score += 30
                // 일반적인 성씨로 시작하는 경우 점수 추가
                if (koreanSurnames.any { line.startsWith(it) }) score += 25
                if (line.length in 2..4) score += 20
                if (!line.contains(Regex("[0-9@.]"))) score += 10
            }
            "company" -> {
                if (companyKeywords.any { line.contains(it) }) {
                    score += 20

                    // 키워드 외에 다른 내용이 있으면 보너스 (실제 상호명일 확률 높음)
                    val keywordText = companyKeywords.first { line.contains(it) }
                    if (line.trim().length > keywordText.length + 2) {
                        score += 15
                    }
                }
                // 회사 설명 문구가 포함된 경우 감점
                if (companyDescriptionKeywords.any { line.contains(it) }) {
                    score -= 20
                }
            }
            "address" -> {
                addressKeywords.forEach { if (line.contains(it)) score += 15 }
                if (line.length > 10) score += 10
            }
        }

        return score
    }

    fun parseContact(text: Text): Contact {
        val contact = Contact()
        val allLines = text.textBlocks.flatMap { it.lines }
            .map { correctOCRErrors(it.text.trim()) }
            .filter { it.isNotBlank() }
        Log.d(TAG, "--- Starting Contact Parsing ---")
        Log.d(TAG, "All corrected lines: $allLines")

        val processedLines = mutableSetOf<String>()
        val allPhoneNumbers = mutableListOf<String>()

        // 1. 전화번호 추출
        Log.d(TAG, "[Phase 1] Extracting Phone Numbers...")
        allLines.forEach { line ->
            val phoneMatcher = phonePattern.matcher(line)
            while (phoneMatcher.find()) {
                val rawPhone = phoneMatcher.group()
                val normalizedPhone = normalizePhoneNumber(rawPhone)
                Log.d(TAG, "Found phone candidate: '$rawPhone' -> Normalized: '$normalizedPhone'")
                if (normalizedPhone.isNotEmpty()) {
                    allPhoneNumbers.add(normalizedPhone)
                    processedLines.add(line)
                }
            }
        }

        if (allPhoneNumbers.isNotEmpty()) {
            contact.phoneNumber = allPhoneNumbers.firstOrNull { it.startsWith("010") } ?: allPhoneNumbers.first()
            Log.d(TAG, "Selected phoneNumber: '${contact.phoneNumber}' from candidates: $allPhoneNumbers")
        }

        // 2. 이메일 추출
        Log.d(TAG, "[Phase 2] Extracting Email...")
        allLines.forEach { line ->
            if (contact.email.isEmpty()) {
                val emailMatcher = emailPattern.matcher(line)
                if (emailMatcher.find()) {
                    contact.email = emailMatcher.group()
                    processedLines.add(line)
                    Log.d(TAG, "Selected email: '${contact.email}'")
                }
            }
        }

        val remainingLines = allLines.filterNot { processedLines.contains(it) }.toMutableList()
        Log.d(TAG, "Remaining lines after Step 1 & 2: $remainingLines")

        // 3. 직책/부서/이름 복합 라인 분석
        Log.d(TAG, "[Phase 3] Analyzing complex lines for Name and Position...")
        val positionLine = remainingLines.firstOrNull { line ->
            positionKeywords.any { line.contains(it) }
        }

        if (positionLine != null) {
            Log.d(TAG, "Found complex line candidate: '$positionLine'")

            val words = positionLine.split(Regex("[\\s/|:]+")).filter { it.isNotBlank() }
            val nameParts = mutableListOf<String>()

            words.forEach { word ->
                var wordProcessed = false

                // "대표홍길동" 같은 결합된 단어 분리
                for (posKey in positionKeywords) {
                    if (word.startsWith(posKey) && word.length > posKey.length) {
                        if (contact.position.isEmpty()) {
                            contact.position = posKey
                            Log.d(TAG, "Separated combined word: position='${posKey}'")
                        }
                        val potentialName = word.substring(posKey.length)
                        nameParts.add(potentialName)
                        Log.d(TAG, "Separated combined word: name part='${potentialName}'")
                        wordProcessed = true
                        break
                    }
                }
                if (wordProcessed) return@forEach

                val isPosition = positionKeywords.contains(word)
                val isDepartment = departmentKeywords.any { word.contains(it) }

                when {
                    isPosition -> {
                        if (contact.position.isEmpty()) contact.position = word
                    }
                    isDepartment -> { // 부서 관련 단어는 이름 후보에서 제외
                        Log.d(TAG, "Filtered out department-related word: '$word'")
                    }
                    else -> {
                        nameParts.add(word)
                    }
                }
            }

            if (contact.position.isNotEmpty()) {
                Log.d(TAG, "Extracted position: '${contact.position}'")
            }

            // [수정된 로직] 이름 후보(nameParts) 중에서 실제 이름 찾기
            if (nameParts.isNotEmpty()) {
                Log.d(TAG, "Name candidates from complex line: $nameParts")

                // 1. 이름 패턴과 성씨로 시작하는 가장 유력한 후보를 찾음
                var foundName = nameParts.firstOrNull { candidate ->
                    koreanNamePattern.matcher(candidate).matches() &&
                            koreanSurnames.any { candidate.startsWith(it) }
                }

                // 2. 만약 없다면, 성씨 조건 없이 이름 패턴(2~4자 한글)에 맞는 후보를 찾음
                if (foundName == null) {
                    foundName = nameParts.firstOrNull { candidate ->
                        koreanNamePattern.matcher(candidate).matches()
                    }
                }

                if (foundName != null) {
                    contact.name = foundName
                    Log.d(TAG, "Selected name from complex line: '${contact.name}'")
                } else {
                    // 3. 개별 단어가 이름이 아닌 경우, 기존처럼 단어들을 조합하여 확인 (예: '홍 길 동' -> '홍길동')
                    val potentialName = nameParts.joinToString("")
                    Log.d(TAG, "No individual name match. Trying to join: '$potentialName'")
                    if (koreanNamePattern.matcher(potentialName).matches() && koreanSurnames.any { potentialName.startsWith(it) }) {
                        contact.name = potentialName
                        Log.d(TAG, "Selected joined name from complex line: '${contact.name}'")
                    }
                }
            }

            remainingLines.remove(positionLine)
        } else {
            Log.d(TAG, "No complex line with position found.")
        }

        // 4. 회사명 추출 (신뢰도 기반)
        Log.d(TAG, "[Phase 4] Extracting Company...")
        val companyLines = remainingLines.filter { line ->
            companyKeywords.any { line.contains(it) }
        }.sortedByDescending { calculateLineConfidence(it, "company") }

        if (companyLines.isNotEmpty()) {
            contact.company = companyLines.first()
            remainingLines.remove(companyLines.first())
            Log.d(TAG, "Selected company: '${contact.company}' from candidates: $companyLines")
        } else {
            Log.d(TAG, "No company candidates found.")
        }

        // 5. 주소 추출
        Log.d(TAG, "[Phase 5] Extracting Address...")
        val addressLines = remainingLines.filter { line ->
            addressKeywords.any { line.contains(it) } && line.length > 5
        }.sortedByDescending { calculateLineConfidence(it, "address") }

        if (addressLines.isNotEmpty()) {
            contact.address = addressLines.first()
            remainingLines.remove(addressLines.first())
            Log.d(TAG, "Selected address: '${contact.address}' from candidates: $addressLines")
        } else {
            Log.d(TAG, "No address candidates found.")
        }

        // 6. 이름이 아직 없으면 남은 라인에서 추출
        if (contact.name.isEmpty()) {
            Log.d(TAG, "[Phase 6] Extracting Name from remaining lines...")
            val nameCandidates = remainingLines
                .filter { line ->
                    koreanNamePattern.matcher(line).matches() &&
                            koreanSurnames.any { line.startsWith(it) } &&
                            !departmentKeywords.any{ line.contains(it) }
                }
                .sortedByDescending { calculateLineConfidence(it, "name") }

            if (nameCandidates.isNotEmpty()) {
                contact.name = nameCandidates.first()
                remainingLines.remove(contact.name)
                Log.d(TAG, "Selected name: '${contact.name}' from candidates: $nameCandidates")
            } else if (remainingLines.isNotEmpty()) {
                // 최후의 수단으로 남은 라인 중 가장 짧은 것을 이름으로 간주 (성씨 조건 없이)
                val fallbackName = remainingLines.filterNot { departmentKeywords.any { dk -> it.contains(dk) } }.minByOrNull { it.length }
                if (fallbackName != null) {
                    contact.name = fallbackName
                    remainingLines.remove(contact.name)
                    Log.d(TAG, "Selected name (fallback): '${contact.name}' from remaining lines: $remainingLines")
                }
            } else {
                Log.d(TAG, "No name candidates found in remaining lines.")
            }
        }

        // 7. 직책이 아직 없으면 남은 라인에서 찾기
        if (contact.position.isEmpty()) {
            Log.d(TAG, "[Phase 7] Extracting Position from remaining lines...")
            remainingLines.forEach { line ->
                val position = extractPosition(line)
                if (position != null) {
                    contact.position = position
                    remainingLines.remove(line)
                    Log.d(TAG, "Selected position (fallback): '${contact.position}' from line: '$line'")
                    return@forEach
                }
            }
        }

        Log.d(TAG, "--- Finished Contact Parsing ---")
        Log.d(TAG, "Final Parsed Contact: $contact")
        return contact
    }
}
