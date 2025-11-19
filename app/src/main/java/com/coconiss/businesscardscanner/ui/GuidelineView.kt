package com.coconiss.businesscardscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GuidelineView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private lateinit var guidelineRect: RectF

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the guideline
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val cardWidth = viewWidth * 0.9f
        val cardHeight = cardWidth / 1.618f // Golden ratio for business cards
        val left = (viewWidth - cardWidth) / 2
        val top = (viewHeight - cardHeight) / 2
        val right = left + cardWidth
        val bottom = top + cardHeight
        guidelineRect = RectF(left, top, right, bottom)
        canvas.drawRect(guidelineRect, paint)
    }

    fun getGuidelineRect(): RectF {
        return guidelineRect
    }
}