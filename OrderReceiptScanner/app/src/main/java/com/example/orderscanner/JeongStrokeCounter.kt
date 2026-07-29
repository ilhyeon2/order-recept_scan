package com.example.orderscanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * 한자 '正'(바를 정) 자 모양의 획수를 분석하여 수량으로 치환하는 전용 로직 클래스
 * 
 * 획수 규칙:
 * - 1획: ㅡ (1개)
 * - 2획: 丅 또는 T (2개)
 * - 3획: 丅 + 가운데 가지 (3개)
 * - 4획: ┯ + 좌/우 가지 (4개)
 * - 5획: 正 (완성형 5개)
 */
object JeongStrokeCounter {

    /**
     * 이미지의 수량 영역 Bitmap을 전달받아 '正' 자 획수(수량)를 카운트합니다.
     */
    fun countStrokesFromBitmap(cropBitmap: Bitmap): Int {
        val width = cropBitmap.width
        val height = cropBitmap.height
        if (width <= 0 || height <= 0) return 0

        // 1. 이진화 (Binarization): 어두운 펜 선 픽셀 추출
        var blackPixelCount = 0
        val isBlackArray = Array(width) { BooleanArray(height) }

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = cropBitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

                // 어두운 필기 픽셀 판별 (임계값 120 이하)
                if (brightness < 120) {
                    isBlackArray[x][y] = true
                    blackPixelCount++
                } else {
                    isBlackArray[x][y] = false
                }
            }
        }

        // 필기 흔적이 없는 빈 영역인 경우
        val totalPixels = width * height
        val fillRatio = blackPixelCount.toDouble() / totalPixels
        if (fillRatio < 0.015) {
            return 0
        }

        // 2. 가로선/세로선 교차점 및 라인 구조 분석
        val horizontalStrokes = detectHorizontalLines(isBlackArray, width, height)
        val verticalStrokes = detectVerticalLines(isBlackArray, width, height)

        val totalDetectedLines = horizontalStrokes + verticalStrokes

        // 3. 획수 결정 (정 자 형태 추론)
        return when {
            totalDetectedLines <= 1 -> 1 // ㅡ (1획)
            totalDetectedLines == 2 -> 2 // 丅 또는 T (2획)
            totalDetectedLines == 3 -> 3 // 3획
            totalDetectedLines == 4 -> 4 // 4획
            totalDetectedLines >= 5 -> 5 // 완성된 正 자 (5획)
            else -> 1
        }
    }

    /**
     * OCR로 추출된 텍스트 신호 중 'T', 'ㅡ', '正', '1', '2' 등의 손글씨 기호를 획수로 치환
     */
    fun parseTextToQuantity(rawText: String): Int {
        val clean = rawText.trim().uppercase()
        
        if (clean.isEmpty()) return 0
        
        // 정 자 형태 매핑
        if (clean.contains("正")) return 5
        if (clean.contains("T") || clean.contains("丅") || clean.contains("丁")) return 2
        if (clean.contains("一") || clean.contains("ㅡ") || clean.contains("-")) return 1
        if (clean.contains("丄") || clean.contains("土")) return 3

        // 아라비아 숫자인 경우
        val numberMatches = "[0-9]+".toRegex().find(clean)
        if (numberMatches != null) {
            return numberMatches.value.toIntOrDefault(1)
        }

        return 1
    }

    private fun detectHorizontalLines(isBlack: Array<BooleanArray>, w: Int, h: Int): Int {
        var lineCount = 0
        val minLength = w * 0.35 // 영역 가로 길이의 35% 이상이면 가로 획으로 인정
        
        for (y in 0 until h step 3) {
            var continuousCount = 0
            for (x in 0 until w) {
                if (isBlack[x][y]) {
                    continuousCount++
                } else {
                    if (continuousCount >= minLength) {
                        lineCount++
                    }
                    continuousCount = 0
                }
            }
            if (continuousCount >= minLength) lineCount++
        }
        return max(1, lineCount)
    }

    private fun detectVerticalLines(isBlack: Array<BooleanArray>, w: Int, h: Int): Int {
        var lineCount = 0
        val minLength = h * 0.35 // 영역 세로 길이의 35% 이상이면 세로 획으로 인정

        for (x in 0 until w step 3) {
            var continuousCount = 0
            for (y in 0 until h) {
                if (isBlack[x][y]) {
                    continuousCount++
                } else {
                    if (continuousCount >= minLength) {
                        lineCount++
                    }
                    continuousCount = 0
                }
            }
            if (continuousCount >= minLength) lineCount++
        }
        return lineCount
    }

    private fun String.toIntOrDefault(default: Int): Int {
        return try { this.toInt() } catch (e: Exception) { default }
    }
}
