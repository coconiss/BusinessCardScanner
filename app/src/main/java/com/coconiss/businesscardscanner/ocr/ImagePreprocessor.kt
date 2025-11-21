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

class ImagePreprocessor {

    private val TAG = "ImagePreprocessor"

    /**
     * 명함 이미지 전처리 메인 함수
     */
    fun preprocessBusinessCard(bitmap: Bitmap): Bitmap {
        try {
            // 1. Bitmap을 OpenCV Mat으로 변환
            val originalMat = Mat()
            Utils.bitmapToMat(bitmap, originalMat)

            // 2. 그레이스케일 변환
            val grayMat = Mat()
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // 3. 노이즈 제거 (Gaussian Blur)
            val blurredMat = Mat()
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

            // 4. 명함 영역 검출 및 원근 변환
            val warpedMat = detectAndWarpCard(blurredMat, originalMat) ?: blurredMat

            // 5. 조명 보정 (CLAHE - Contrast Limited Adaptive Histogram Equalization)
            val enhancedMat = enhanceContrast(warpedMat)

            // 6. 적응형 이진화
            val binaryMat = adaptiveThreshold(enhancedMat)

            // 7. 모폴로지 연산으로 노이즈 제거
            val cleanedMat = morphologyClean(binaryMat)

            // 8. 선명도 향상
            val sharpenedMat = sharpenImage(cleanedMat)

            // 9. Mat을 Bitmap으로 변환
            val resultBitmap = Bitmap.createBitmap(
                sharpenedMat.cols(),
                sharpenedMat.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(sharpenedMat, resultBitmap)

            // 메모리 해제
            originalMat.release()
            grayMat.release()
            blurredMat.release()
            warpedMat.release()
            enhancedMat.release()
            binaryMat.release()
            cleanedMat.release()
            sharpenedMat.release()

            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "전처리 실패, 원본 반환: ${e.message}")
            return bitmap
        }
    }

    /**
     * 명함 영역 검출 및 원근 변환
     */
    private fun detectAndWarpCard(grayMat: Mat, originalMat: Mat): Mat? {
        try {
            // Canny 엣지 검출
            val edges = Mat()
            Imgproc.Canny(grayMat, edges, 50.0, 150.0)

            // 윤곽선 찾기
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 가장 큰 사각형 윤곽선 찾기
            var maxArea = 0.0
            var maxContour: MatOfPoint? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > maxArea && area > grayMat.rows() * grayMat.cols() * 0.1) {
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
                        maxArea = area
                        maxContour = MatOfPoint(*approx.toArray())
                    }
                }
            }

            edges.release()
            hierarchy.release()

            // 명함 윤곽선을 찾았으면 원근 변환 수행
            if (maxContour != null) {
                return perspectiveTransform(grayMat, maxContour)
            }

            return null

        } catch (e: Exception) {
            Log.e(TAG, "명함 영역 검출 실패: ${e.message}")
            return null
        }
    }

    /**
     * 원근 변환
     */
    private fun perspectiveTransform(mat: Mat, contour: MatOfPoint): Mat {
        val points = contour.toArray()

        // 꼭지점 정렬 (좌상, 우상, 우하, 좌하)
        val sortedPoints = orderPoints(points)

        // 변환 후 이미지 크기 계산
        val widthA = distance(sortedPoints[2], sortedPoints[3])
        val widthB = distance(sortedPoints[1], sortedPoints[0])
        val maxWidth = max(widthA, widthB).toInt()

        val heightA = distance(sortedPoints[1], sortedPoints[2])
        val heightB = distance(sortedPoints[0], sortedPoints[3])
        val maxHeight = max(heightA, heightB).toInt()

        // 목적지 좌표
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )

        val src = MatOfPoint2f(*sortedPoints)

        // 변환 행렬 계산 및 적용
        val transformMatrix = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(
            mat,
            warped,
            transformMatrix,
            Size(maxWidth.toDouble(), maxHeight.toDouble())
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
        val sorted = points.sortedBy { it.x + it.y }
        val topLeft = sorted[0]
        val bottomRight = sorted[3]

        val remaining = points.filter { it != topLeft && it != bottomRight }
        val topRight = remaining.minByOrNull { it.y } ?: remaining[0]
        val bottomLeft = remaining.firstOrNull { it != topRight } ?: remaining[1]

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
     * CLAHE를 사용한 대비 향상
     */
    private fun enhanceContrast(mat: Mat): Mat {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(mat, enhanced)
        return enhanced
    }

    /**
     * 적응형 이진화
     */
    private fun adaptiveThreshold(mat: Mat): Mat {
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            mat,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            15,
            10.0
        )
        return binary
    }

    /**
     * 모폴로지 연산으로 노이즈 제거
     */
    private fun morphologyClean(mat: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(2.0, 2.0)
        )

        val cleaned = Mat()
        // Opening: 작은 노이즈 제거
        Imgproc.morphologyEx(mat, cleaned, Imgproc.MORPH_OPEN, kernel)

        // Closing: 글자 내부의 작은 구멍 채우기
        Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_CLOSE, kernel)

        kernel.release()
        return cleaned
    }

    /**
     * 이미지 선명도 향상
     */
    private fun sharpenImage(mat: Mat): Mat {
        val sharpened = Mat()
        val kernel = Mat(3, 3, CvType.CV_32F)

        // 샤프닝 커널
        kernel.put(0, 0,
            0.0, -1.0, 0.0,
            -1.0, 5.0, -1.0,
            0.0, -1.0, 0.0
        )

        Imgproc.filter2D(mat, sharpened, -1, kernel)
        kernel.release()

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

        // 대비 향상
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