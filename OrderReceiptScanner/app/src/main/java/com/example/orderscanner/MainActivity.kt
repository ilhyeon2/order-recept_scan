package com.example.orderscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.orderscanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.Serializable
import kotlin.math.abs

data class OrderItem(
    val menuName: String,
    val unitPrice: Int,
    val quantity: Int,
    val totalPrice: Int
) : Serializable

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(false)
        .setPageLimit(1)
        .setResultFormats(RESULT_FORMAT_JPEG)
        .setScannerMode(SCANNER_MODE_FULL)
        .build()

    private val scanner by lazy { GmsDocumentScanning.getClient(scannerOptions) }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val imageUri = scanResult?.pages?.get(0)?.imageUri
            if (imageUri != null) {
                processImageWithKoreanOCR(imageUri)
            } else {
                showRetakeDialog("이미지 데이터를 불러오지 못했습니다. 다시 촬영해 주세요.")
            }
        } else {
            Toast.makeText(this, "촬영이 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScanOrder.setOnClickListener {
            launchDocumentScanner()
        }
    }

    private fun launchDocumentScanner() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "카메라 스캐너 실행 실패: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun processImageWithKoreanOCR(imageUri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, imageUri))
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    parseOrderSheetAndValidate(visionText)
                }
                .addOnFailureListener {
                    showRetakeDialog("텍스트 인식이 불가능합니다. 빛 반사가 없는 곳에서 재촬영해 주세요.")
                }
        } catch (e: Exception) {
            showRetakeDialog("이미지 처리 중 오류가 발생했습니다. 재촬영해 주세요.")
        }
    }

    private fun parseOrderSheetAndValidate(visionText: Text) {
        val orderItemList = mutableListOf<OrderItem>()
        var calculatedGrandTotal = 0

        // 1. OCR 인식된 모든 라인을 Y축(높이) 기준으로 같은 행(Row)끼리 그룹화
        val allLines = visionText.textBlocks.flatMap { it.lines }
        val rows = mutableListOf<MutableList<Text.Line>>()
        val lineThreshold = 35 // 같은 행으로 판단할 Y축 오차 범위(px)

        val sortedLines = allLines.sortedBy { it.boundingBox?.top ?: 0 }

        for (line in sortedLines) {
            val box = line.boundingBox ?: continue
            val lineCenterY = box.top + box.height() / 2

            val matchedRow = rows.find { row ->
                val rowCenterY = row.map { (it.boundingBox?.top ?: 0) + (it.boundingBox?.height() ?: 0) / 2 }.average()
                abs(lineCenterY - rowCenterY) < lineThreshold
            }

            if (matchedRow != null) {
                matchedRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        // 2. 각 행별로 [메뉴명 / 단가 / 수량(우측 손글씨)] 정밀 분석
        for (row in rows) {
            val sortedElements = row.sortedBy { it.boundingBox?.left ?: 0 }
            val rowText = sortedElements.joinToString(" ") { it.text }

            // 단가(숫자) 추출 (예: 35,000 -> 35000)
            val priceMatch = "[0-9]{1,3}(,[0-9]{3})+|[0-9]{4,6}".toRegex().find(rowText) ?: continue
            val unitPrice = priceMatch.value.replace(",", "").toIntOrNull() ?: continue

            // 1,000원 미만이거나 '합계' 행은 제외
            if (unitPrice < 1000 || rowText.contains("합계")) continue

            // 단가 텍스트보다 오른쪽에 위치한 수량(손글씨) 영역 검색
            val priceElement = sortedElements.find { it.text.contains(priceMatch.value) }
            val priceRightX = priceElement?.boundingBox?.right ?: 0

            val quantityElements = sortedElements.filter { (it.boundingBox?.left ?: 0) >= priceRightX - 10 }
            val qtyText = quantityElements.joinToString("") { it.text }.trim()

            // 우측 수량란이 비어있으면(손글씨가 없으면) 미주문 메뉴이므로 무시
            val quantity = parseJeongQuantity(qtyText)
            if (quantity <= 0) continue

            // 메뉴명 추출 (단가 왼쪽 영역)
            var menuName = rowText.substring(0, rowText.indexOf(priceMatch.value))
                .replace("[0-9]|,|원|\\s|\\(1인\\)".toRegex(), "")
                .trim()

            if (menuName.length < 2) {
                menuName = "주문 메뉴"
            }

            val itemTotal = unitPrice * quantity
            calculatedGrandTotal += itemTotal
            orderItemList.add(OrderItem(menuName, unitPrice, quantity, itemTotal))
        }

        if (orderItemList.isEmpty()) {
            showRetakeDialog("인식된 주문 내역이 없습니다. 수량이 표시된 부분을 바르게 재촬영해 주세요.")
            return
        }

        val intent = Intent(this, ReceiptActivity::class.java).apply {
            putExtra("ORDER_ITEMS", ArrayList(orderItemList))
            putExtra("TOTAL_PRICE", calculatedGrandTotal)
        }
        startActivity(intent)
    }

    private fun parseJeongQuantity(text: String): Int {
        if (text.isEmpty()) return 0

        // 바를 정(正) 자 및 손글씨 기호(T, ㅡ) 변환
        if (text.contains("正")) return 5
        if (text.contains("T") || text.contains("t") || text.contains("r") || text.contains("TT")) return 2
        if (text.contains("ㅡ") || text.contains("-") || text.contains("1") || text.contains("|")) return 1

        val num = text.replace("[^0-9]".toRegex(), "").toIntOrNull()
        if (num != null && num in 1..99) {
            return num
        }

        return 0
    }

    private fun showRetakeDialog(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("재촬영 요구")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("다시 촬영하기") { _, _ ->
                launchDocumentScanner()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
