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
     * 명함 이미지 전처리 메인 함수 (균형잡힌 버전)
     * @param enableWarp 명함 영역 자동 검출 및 원근 변환 활성화 여부
     */
    fun preprocessBusinessCard(bitmap: Bitmap, enableWarp: Boolean = true): Bitmap {
        try {
            // 1. Bitmap을 OpenCV Mat으로 변환
            val originalMat = Mat()
            Utils.bitmapToMat(bitmap, originalMat)

            // 1.1 이미지 회전 (오른쪽으로 90도 회전된 이미지를 올바르게 복원: 반시계 방향으로 90도 회전)
            val rotatedMat = Mat()
            Core.rotate(originalMat, rotatedMat, -90)
            originalMat.release()

            // 2. 그레이스케일 변환
            val grayMat = Mat()
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // 3. 노이즈 제거 (약하게)
            val denoised = Mat()
            Imgproc.GaussianBlur(grayMat, denoised, Size(3.0, 3.0), 0.0)

            // 4. 명함 영역 검출 및 원근 변환 (선택적)
            val warpedMat = if (enableWarp) {
                detectAndWarpCard(denoised, originalMat)
            } else {
                null
            }

            // 5. 조명 보정 (부드럽게)
            val illuminationCorrected = if (warpedMat != null) {
                correctIlluminationGentle(warpedMat)
            } else {
                correctIlluminationGentle(denoised)
            }

            // 6. 대비 향상 (CLAHE - 약하게)
            val enhanced = Mat()
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(illuminationCorrected, enhanced)

            // 7. 적응형 이진화 (부드럽게)
            val binary = Mat()
            Imgproc.adaptiveThreshold(
                enhanced,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                15,  // 블록 크기
                10.0  // C 값
            )

            // 8. 작은 노이즈만 제거 (최소한의 모폴로지)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            val cleaned = Mat()
            Imgproc.morphologyEx(binary, cleaned, Imgproc.MORPH_OPEN, kernel)

            // 9. Mat을 Bitmap으로 변환
            val resultBitmap = Bitmap.createBitmap(
                cleaned.cols(),
                cleaned.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(cleaned, resultBitmap)

            // 메모리 해제
            originalMat.release()
            grayMat.release()
            denoised.release()
            warpedMat?.release()
            illuminationCorrected.release()
            enhanced.release()
            binary.release()
            cleaned.release()
            kernel.release()

            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "전처리 실패, 간단한 전처리 사용: ${e.message}")
            e.printStackTrace()
            return simplePreprocess(bitmap)
        }
    }

    /**
     * 부드러운 조명 보정
     */
    private fun correctIlluminationGentle(grayMat: Mat): Mat {
        // 배경 추정 (크게 블러)
        val background = Mat()
        Imgproc.GaussianBlur(grayMat, background, Size(51.0, 51.0), 0.0)

        // 배경 제거 및 정규화
        val corrected = Mat()
        Core.subtract(grayMat, background, corrected)
        Core.normalize(corrected, corrected, 0.0, 255.0, Core.NORM_MINMAX)

        background.release()
        return corrected
    }

    /**
     * 명함 영역 검출 및 원근 변환 (개선 버전)
     */
    private fun detectAndWarpCard(grayMat: Mat, originalMat: Mat): Mat? {
        try {
            val imageArea = grayMat.rows() * grayMat.cols().toDouble()

            // 1단계: 다양한 임계값으로 엣지 검출
            val edges1 = Mat()
            val edges2 = Mat()
            val edges3 = Mat()

            Imgproc.Canny(grayMat, edges1, 30.0, 100.0)
            Imgproc.Canny(grayMat, edges2, 50.0, 150.0)
            Imgproc.Canny(grayMat, edges3, 75.0, 200.0)

            // 엣지 결합
            val combinedEdges = Mat()
            Core.bitwise_or(edges1, edges2, combinedEdges)
            Core.bitwise_or(combinedEdges, edges3, combinedEdges)

            edges1.release()
            edges2.release()
            edges3.release()

            // 2단계: 엣지 연결을 위한 팽창 (Dilation)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(combinedEdges, combinedEdges, kernel)
            kernel.release()

            // 3단계: 윤곽선 찾기
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                combinedEdges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            Log.d(TAG, "발견된 윤곽선 개수: ${contours.size}")

            // 4단계: 명함 후보 선별
            data class CardCandidate(
                val contour: MatOfPoint,
                val area: Double,
                val aspectRatio: Double,
                val score: Double
            )

            val candidates = mutableListOf<CardCandidate>()

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)

                // 면적이 너무 작거나 크면 제외 (이미지의 30% ~ 98%)
                if (area < imageArea * 0.3 || area > imageArea * 0.98) {
                    continue
                }

                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()

                // 다양한 epsilon 값으로 시도
                for (epsilon in listOf(0.01, 0.02, 0.03, 0.04)) {
                    Imgproc.approxPolyDP(
                        MatOfPoint2f(*contour.toArray()),
                        approx,
                        epsilon * peri,
                        true
                    )

                    // 4개의 꼭지점을 가진 윤곽선 찾기
                    if (approx.rows() == 4) {
                        val rect = Imgproc.boundingRect(contour)
                        val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

                        // 명함 종횡비 확인 (가로 명함 1.3:1 ~ 2.2:1 또는 세로 명함)
                        val isHorizontalCard = aspectRatio in 1.3..2.2
                        val isVerticalCard = aspectRatio in 0.45..0.77  // 1/2.2 ~ 1/1.3

                        if (isHorizontalCard || isVerticalCard) {
                            // 점수 계산
                            var score = 0.0

                            // 면적 점수 (이미지의 50% 이상이면 가산점)
                            val areaRatio = area / imageArea
                            score += if (areaRatio > 0.5) 100.0 else areaRatio * 100.0

                            // 종횡비 점수 (명함 표준 비율 1.6:1에 가까울수록)
                            val idealRatio = 1.6
                            val ratioDiff = if (isHorizontalCard) {
                                kotlin.math.abs(aspectRatio - idealRatio)
                            } else {
                                kotlin.math.abs(aspectRatio - 1.0 / idealRatio)
                            }
                            score += (1.0 - ratioDiff) * 50.0

                            // 볼록성 확인 (볼록하면 가산점)
                            val isConvex = Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))
                            if (isConvex) score += 30.0

                            // 직사각형에 가까운지 확인
                            val rectangularity = area / (rect.width * rect.height).toDouble()
                            score += rectangularity * 20.0

                            Log.d(TAG, "후보 발견 - 면적비: ${"%.2f".format(areaRatio)}, " +
                                    "종횡비: ${"%.2f".format(aspectRatio)}, 점수: ${"%.2f".format(score)}")

                            candidates.add(
                                CardCandidate(
                                    MatOfPoint(*approx.toArray()),
                                    area,
                                    aspectRatio,
                                    score
                                )
                            )
                            break  // 이 윤곽선에 대해 적절한 근사를 찾았으므로 중단
                        }
                    }
                }
            }

            combinedEdges.release()
            hierarchy.release()

            // 5단계: 가장 높은 점수의 후보 선택
            val bestCandidate = candidates.maxByOrNull { it.score }

            if (bestCandidate != null && bestCandidate.score > 100.0) {
                Log.d(TAG, "명함 영역 검출 성공 - 점수: ${"%.2f".format(bestCandidate.score)}, " +
                        "면적비: ${"%.2f".format(bestCandidate.area / imageArea)}")
                return perspectiveTransform(grayMat, bestCandidate.contour)
            } else {
                Log.d(TAG, "명함 영역 검출 실패 - 적절한 후보 없음 " +
                        "(최고 점수: ${bestCandidate?.score ?: 0.0})")
            }

            return null

        } catch (e: Exception) {
            Log.e(TAG, "명함 영역 검출 실패: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 원근 변환
     */
    private fun perspectiveTransform(mat: Mat, contour: MatOfPoint): Mat {
        val points = contour.toArray()

        // 꼭지점 정렬
        val sortedPoints = orderPoints(points)

        // 변환 후 크기 계산
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

        // 변환
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
        val sortedBySum = points.sortedBy { it.x + it.y }
        val topLeft = sortedBySum[0]
        val bottomRight = sortedBySum[3]

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
     * 간단하고 안전한 전처리 (OpenCV 없이)
     */
    fun simplePreprocess(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 약한 대비 향상
        val contrast = 1.3f
        val brightness = 5

        for (i in pixels.indices) {
            val color = pixels[i]
            var r = Color.red(color)
            var g = Color.green(color)
            var b = Color.blue(color)

            // 대비 및 밝기 조정
            r = clamp((contrast * (r - 128) + 128 + brightness).toInt())
            g = clamp((contrast * (g - 128) + 128 + brightness).toInt())
            b = clamp((contrast * (g - 128) + 128 + brightness).toInt())

            pixels[i] = Color.rgb(r, g, b)
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun clamp(value: Int): Int {
        return max(0, min(255, value))
    }

    /**
     * 최소 전처리 버전 (가장 안전)
     * - 그레이스케일만 적용
     * - 조명 보정만 약하게
     */
    fun minimalPreprocess(bitmap: Bitmap): Bitmap {
        try {
            val originalMat = Mat()
            Utils.bitmapToMat(bitmap, originalMat)

            // 그레이스케일
            val grayMat = Mat()
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // 약한 가우시안 블러
            val blurred = Mat()
            Imgproc.GaussianBlur(grayMat, blurred, Size(3.0, 3.0), 0.0)

            // 약한 CLAHE
            val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
            val enhanced = Mat()
            clahe.apply(blurred, enhanced)

            // Bitmap 변환
            val resultBitmap = Bitmap.createBitmap(
                enhanced.cols(),
                enhanced.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(enhanced, resultBitmap)

            // 메모리 해제
            originalMat.release()
            grayMat.release()
            blurred.release()
            enhanced.release()

            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "최소 전처리 실패, 원본 반환: ${e.message}")
            return bitmap
        }
    }
}