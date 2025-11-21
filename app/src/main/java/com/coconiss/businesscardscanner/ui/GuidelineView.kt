package com.coconiss.businesscardscanner.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class GuidelineView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val TAG = "GuidelineView"

    // 가이드라인 테두리
    private val guidePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    // 모서리 강조
    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // 반투명 배경
    private val backgroundPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // 가이드 텍스트
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private lateinit var guidelineRect: RectF

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateGuidelineRect()
    }

    /**
     * 가이드라인 사각형 계산
     */
    private fun calculateGuidelineRect() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 명함 표준 비율 (ISO 7810 ID-1: 85.60 × 53.98 mm ≈ 1.586:1)
        val cardAspectRatio = 1.586f

        // 화면의 85% 크기로 설정
        val guideWidth = viewWidth * 0.85f
        val guideHeight = guideWidth / cardAspectRatio

        // 가이드라인이 화면을 벗어나지 않도록 조정
        val finalGuideHeight = if (guideHeight > viewHeight * 0.7f) {
            viewHeight * 0.7f
        } else {
            guideHeight
        }
        val finalGuideWidth = finalGuideHeight * cardAspectRatio

        // 중앙 배치
        val left = (viewWidth - finalGuideWidth) / 2f
        val top = (viewHeight - finalGuideHeight) / 2f
        val right = left + finalGuideWidth
        val bottom = top + finalGuideHeight

        guidelineRect = RectF(left, top, right, bottom)

        Log.d(TAG, "GuidelineView 크기 계산됨:")
        Log.d(TAG, "뷰 크기: ${width} x ${height}")
        Log.d(TAG, "가이드라인: left=$left, top=$top, width=$finalGuideWidth, height=$finalGuideHeight")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!::guidelineRect.isInitialized) {
            calculateGuidelineRect()
        }

        // 1. 반투명 배경 (가이드라인 밖 영역 어둡게)
        val path = Path()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(guidelineRect, Path.Direction.CCW)
        canvas.drawPath(path, backgroundPaint)

        // 2. 가이드라인 테두리
        canvas.drawRect(guidelineRect, guidePaint)

        // 3. 모서리 강조 (L자 형태)
        val cornerLength = 60f

        // 좌상단
        canvas.drawLine(
            guidelineRect.left, guidelineRect.top,
            guidelineRect.left + cornerLength, guidelineRect.top,
            cornerPaint
        )
        canvas.drawLine(
            guidelineRect.left, guidelineRect.top,
            guidelineRect.left, guidelineRect.top + cornerLength,
            cornerPaint
        )

        // 우상단
        canvas.drawLine(
            guidelineRect.right, guidelineRect.top,
            guidelineRect.right - cornerLength, guidelineRect.top,
            cornerPaint
        )
        canvas.drawLine(
            guidelineRect.right, guidelineRect.top,
            guidelineRect.right, guidelineRect.top + cornerLength,
            cornerPaint
        )

        // 우하단
        canvas.drawLine(
            guidelineRect.right, guidelineRect.bottom,
            guidelineRect.right - cornerLength, guidelineRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            guidelineRect.right, guidelineRect.bottom,
            guidelineRect.right, guidelineRect.bottom - cornerLength,
            cornerPaint
        )

        // 좌하단
        canvas.drawLine(
            guidelineRect.left, guidelineRect.bottom,
            guidelineRect.left + cornerLength, guidelineRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            guidelineRect.left, guidelineRect.bottom,
            guidelineRect.left, guidelineRect.bottom - cornerLength,
            cornerPaint
        )

        // 4. 안내 텍스트
        canvas.drawText(
            "명함을 가이드라인 안에 맞춰주세요",
            width / 2f,
            guidelineRect.top - 40f,
            textPaint
        )
    }

    /**
     * 가이드라인 사각형 반환
     */
    fun getGuidelineRect(): RectF {
        if (!::guidelineRect.isInitialized) {
            calculateGuidelineRect()
        }
        Log.d(TAG, "getGuidelineRect 호출됨: $guidelineRect")
        return guidelineRect
    }
}