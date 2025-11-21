package com.coconiss.businesscardscanner.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.abs

class ImagePreprocessor {

    private val TAG = "ImagePreprocessor"

    /**
     * 명함 이미지 전처리 메인 함수 (개선 버전)
     */
    fun preprocessBusinessCard(bitmap: Bitmap): Bitmap {
        try {
            // 1. Bitmap을 OpenCV Mat으로 변환
            val originalMat = Mat()
            Utils.bitmapToMat(bitmap, originalMat)

            // 2. 그레이스케일 변환
            val grayMat = Mat()
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // 3. 조명 불균형 보정 (추가)
            val illuminationCorrected = correctIllumination(grayMat)

            // 4. 노이즈 제거 (양방향 필터로 변경 - 엣지 보존)
            val denoised = Mat()
            Imgproc.bilateralFilter(illuminationCorrected, denoised, 9, 75.0, 75.0)

            // 5. 명함 영역 검출 및 원근 변환 (개선)
            val warpedMat = detectAndWarpCardImproved(denoised, originalMat) ?: denoised

            // 6. CLAHE 적용 (파라미터 조정)
            val enhancedMat = enhanceContrastImproved(warpedMat)

            // 7. 다중 임계값 적응형 이진화
            val binaryMat = multiScaleAdaptiveThreshold(enhancedMat)

            // 8. 텍스트 영역 강조 (추가)
            val textEnhanced = enhanceTextRegions(binaryMat)

            // 9. 모폴로지 연산으로 노이즈 제거
            val cleanedMat = morphologyCleanImproved(textEnhanced)

            // 10. 선명도 향상 (언샤프 마스크)
            val sharpenedMat = unsharpMask(cleanedMat)

            // 11. Mat을 Bitmap으로 변환
            val resultBitmap = Bitmap.createBitmap(
                sharpenedMat.cols(),
                sharpenedMat.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(sharpenedMat, resultBitmap)

            // 메모리 해제
            originalMat.release()
            grayMat.release()
            illuminationCorrected.release()
            denoised.release()
            warpedMat.release()
            enhancedMat.release()
            binaryMat.release()
            textEnhanced.release()
            cleanedMat.release()
            sharpenedMat.release()

            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "전처리 실패, 원본 반환: ${e.message}")
            e.printStackTrace()
            return bitmap
        }
    }

    /**
     * 조명 불균형 보정 (새로운 기능)
     * 배경 제거를 통한 조명 정규화
     */
    private fun correctIllumination(grayMat: Mat): Mat {
        // 큰 가우시안 블러로 배경 추정
        val background = Mat()
        Imgproc.GaussianBlur(grayMat, background, Size(51.0, 51.0), 0.0)

        // 배경 제거
        val corrected = Mat()
        Core.subtract(grayMat, background, corrected)

        // 대비 정규화
        Core.normalize(corrected, corrected, 0.0, 255.0, Core.NORM_MINMAX)

        background.release()
        return corrected
    }

    /**
     * 개선된 명함 영역 검출 및 원근 변환
     */
    private fun detectAndWarpCardImproved(grayMat: Mat, originalMat: Mat): Mat? {
        try {
            // 1. 다중 스케일 Canny 엣지 검출
            val edges = multiScaleCannyEdge(grayMat)

            // 2. 모폴로지 연산으로 엣지 연결
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // 3. 윤곽선 찾기
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 4. 명함 후보 찾기 (크기, 종횡비, 볼록성 고려)
            var bestContour: MatOfPoint? = null
            var maxScore = 0.0

            val imageArea = grayMat.rows() * grayMat.cols()

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)

                // 면적이 너무 작거나 크면 제외
                if (area < imageArea * 0.1 || area > imageArea * 0.95) continue

                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(
                    MatOfPoint2f(*contour.toArray()),
                    approx,
                    0.02 * peri,
                    true
                )

                // 4개의 꼭지점을 가진 윤곽선만 선택
                if (approx.rows() == 4) {
                    // 종횡비 확인 (명함은 대략 1.5:1 ~ 2:1)
                    val rect = Imgproc.boundingRect(contour)
                    val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

                    // 볼록성 확인
                    val isConvex = Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))

                    // 점수 계산
                    var score = area
                    if (aspectRatio in 1.3..2.2) score *= 1.5
                    if (isConvex) score *= 1.2

                    if (score > maxScore) {
                        maxScore = score
                        bestContour = MatOfPoint(*approx.toArray())
                    }
                }
            }

            edges.release()
            hierarchy.release()

            // 명함 윤곽선을 찾았으면 원근 변환 수행
            if (bestContour != null) {
                Log.d(TAG, "명함 영역 검출 성공")
                return perspectiveTransformImproved(grayMat, bestContour)
            } else {
                Log.d(TAG, "명함 영역 검출 실패 - 원본 사용")
            }

            return null

        } catch (e: Exception) {
            Log.e(TAG, "명함 영역 검출 실패: ${e.message}")
            return null
        }
    }

    /**
     * 다중 스케일 Canny 엣지 검출
     */
    private fun multiScaleCannyEdge(mat: Mat): Mat {
        val edges1 = Mat()
        val edges2 = Mat()
        val edges3 = Mat()

        // 다양한 임계값으로 엣지 검출
        Imgproc.Canny(mat, edges1, 30.0, 100.0)
        Imgproc.Canny(mat, edges2, 50.0, 150.0)
        Imgproc.Canny(mat, edges3, 100.0, 200.0)

        // 결합
        val combined = Mat()
        Core.bitwise_or(edges1, edges2, combined)
        Core.bitwise_or(combined, edges3, combined)

        edges1.release()
        edges2.release()
        edges3.release()

        return combined
    }

    /**
     * 개선된 원근 변환
     */
    private fun perspectiveTransformImproved(mat: Mat, contour: MatOfPoint): Mat {
        val points = contour.toArray()

        // 꼭지점 정렬 (좌상, 우상, 우하, 좌하)
        val sortedPoints = orderPoints(points)

        // 변환 후 이미지 크기 계산 (더 정확하게)
        val widthA = distance(sortedPoints[2], sortedPoints[3])
        val widthB = distance(sortedPoints[1], sortedPoints[0])
        val maxWidth = max(widthA, widthB).toInt()

        val heightA = distance(sortedPoints[1], sortedPoints[2])
        val heightB = distance(sortedPoints[0], sortedPoints[3])
        val maxHeight = max(heightA, heightB).toInt()

        // 목적지 좌표 (약간의 여백 추가)
        val margin = 10.0
        val dst = MatOfPoint2f(
            Point(margin, margin),
            Point(maxWidth - margin, margin),
            Point(maxWidth - margin, maxHeight - margin),
            Point(margin, maxHeight - margin)
        )

        val src = MatOfPoint2f(*sortedPoints)

        // 변환 행렬 계산 및 적용
        val transformMatrix = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(
            mat,
            warped,
            transformMatrix,
            Size(maxWidth.toDouble(), maxHeight.toDouble()),
            Imgproc.INTER_CUBIC
        )

        transformMatrix.release()
        src.release()
        dst.release()

        return warped
    }

    /**
     * 점 정렬 (좌상, 우상, 우하, 좌하)
     */
    private fun orderPoints(points: Array<Point>): Array<Point> {
        // 합이 가장 작은 점 = 좌상
        // 합이 가장 큰 점 = 우하
        val sortedBySum = points.sortedBy { it.x + it.y }
        val topLeft = sortedBySum[0]
        val bottomRight = sortedBySum[3]

        // 차이가 가장 작은 점 = 우상
        // 차이가 가장 큰 점 = 좌하
        val sortedByDiff = points.sortedBy { it.y - it.x }
        val topRight = sortedByDiff[0]
        val bottomLeft = sortedByDiff[3]

        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * 두 점 사이의 거리
     */
    private fun distance(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 개선된 CLAHE 대비 향상
     */
    private fun enhanceContrastImproved(mat: Mat): Mat {
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(mat, enhanced)
        return enhanced
    }

    /**
     * 다중 스케일 적응형 이진화 (새로운 기능)
     */
    private fun multiScaleAdaptiveThreshold(mat: Mat): Mat {
        val binary1 = Mat()
        val binary2 = Mat()
        val binary3 = Mat()

        // 다양한 블록 크기로 적응형 이진화
        Imgproc.adaptiveThreshold(
            mat, binary1, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 11, 8.0
        )

        Imgproc.adaptiveThreshold(
            mat, binary2, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 21, 10.0
        )

        Imgproc.adaptiveThreshold(
            mat, binary3, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY, 15, 8.0
        )

        // 결과 결합 (AND 연산으로 더 보수적으로)
        val combined = Mat()
        Core.bitwise_and(binary1, binary2, combined)
        Core.bitwise_and(combined, binary3, combined)

        binary1.release()
        binary2.release()
        binary3.release()

        return combined
    }

    /**
     * 텍스트 영역 강조 (새로운 기능)
     */
    private fun enhanceTextRegions(mat: Mat): Mat {
        // 수평/수직 모폴로지로 텍스트 라인 강조
        val horizontalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(25.0, 1.0)
        )
        val verticalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, 25.0)
        )

        val horizontal = Mat()
        val vertical = Mat()

        Imgproc.morphologyEx(mat, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
        Imgproc.morphologyEx(mat, vertical, Imgproc.MORPH_OPEN, verticalKernel)

        // 텍스트가 아닌 영역 제거
        val nonText = Mat()
        Core.bitwise_or(horizontal, vertical, nonText)

        val result = Mat()
        Core.subtract(mat, nonText, result)

        horizontalKernel.release()
        verticalKernel.release()
        horizontal.release()
        vertical.release()
        nonText.release()

        return result
    }

    /**
     * 개선된 모폴로지 노이즈 제거
     */
    private fun morphologyCleanImproved(mat: Mat): Mat {
        // 작은 노이즈 제거
        val kernel1 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val opened = Mat()
        Imgproc.morphologyEx(mat, opened, Imgproc.MORPH_OPEN, kernel1)

        // 글자 내부 구멍 채우기
        val kernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, kernel2)

        kernel1.release()
        kernel2.release()
        opened.release()

        return closed
    }

    /**
     * 언샤프 마스크 선명도 향상 (개선)
     */
    private fun unsharpMask(mat: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(0.0, 0.0), 3.0)

        val sharpened = Mat()
        Core.addWeighted(mat, 1.5, blurred, -0.5, 0.0, sharpened)

        blurred.release()
        return sharpened
    }

    /**
     * 간단한 전처리 (OpenCV 없이)
     */
    fun simplePreprocess(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 대비 향상 및 밝기 조정
        val contrast = 1.5f
        val brightness = 10

        for (i in pixels.indices) {
            val color = pixels[i]
            var r = Color.red(color)
            var g = Color.green(color)
            var b = Color.blue(color)

            // 대비 및 밝기 조정
            r = clamp((contrast * (r - 128) + 128 + brightness).toInt())
            g = clamp((contrast * (g - 128) + 128 + brightness).toInt())
            b = clamp((contrast * (b - 128) + 128 + brightness).toInt())

            pixels[i] = Color.rgb(r, g, b)
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun clamp(value: Int): Int {
        return max(0, min(255, value))
    }
}