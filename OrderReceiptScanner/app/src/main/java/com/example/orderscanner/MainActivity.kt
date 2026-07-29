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

data class MasterMenu(
    val name: String,
    val keywords: List<String>,
    val price: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // [장작떼기] 고정 메뉴판 DB
    private val masterMenuList = listOf(
        MasterMenu("오리주물럭", listOf("오리주물럭", "주물럭"), 35000),
        MasterMenu("오리로스", listOf("오리로스", "로스"), 35000),
        MasterMenu("삼겹살(1인)", listOf("삼겹살", "삼겹"), 13000),
        MasterMenu("추가반마리", listOf("추가반마리", "반마리"), 20000),
        MasterMenu("된장찌개", listOf("된장찌개", "된장"), 2000),
        MasterMenu("볶음밥", listOf("볶음밥", "볶음"), 2000),
        MasterMenu("공기밥", listOf("공기밥", "공기"), 1000),
        MasterMenu("쫄면", listOf("쫄면"), 2000),
        MasterMenu("떡", listOf("떡"), 2000),
        MasterMenu("소주", listOf("소주"), 4000),
        MasterMenu("맥주", listOf("맥주"), 5000),
        MasterMenu("막걸리", listOf("막걸리"), 4000),
        MasterMenu("청하", listOf("청하"), 6000),
        MasterMenu("백세주", listOf("백세주"), 10000),
        MasterMenu("음료수", listOf("음료수", "음료"), 2000)
    )

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
                    parseJangjakOrderSheetByColumn(visionText)
                }
                .addOnFailureListener {
                    showRetakeDialog("텍스트 인식이 불가능합니다. 빛 반사가 없는 곳에서 재촬영해 주세요.")
                }
        } catch (e: Exception) {
            showRetakeDialog("이미지 처리 중 오류가 발생했습니다. 재촬영해 주세요.")
        }
    }

    private fun parseJangjakOrderSheetByColumn(visionText: Text) {
        val orderItemList = mutableListOf<OrderItem>()
        var calculatedGrandTotal = 0

        val allElements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        if (allElements.isEmpty()) {
            showRetakeDialog("인식된 텍스트가 없습니다. 다시 촬영해 주세요.")
            return
        }

        // 1. "수량"이라는 헤더 단어의 X 좌표 위치를 찾아 수량 열(Column)의 기준선 설정 (Int 타입 유지)
        val quantityHeader = allElements.find { it.text.contains("수량") }
        val maxRight = allElements.maxOfOrNull { it.boundingBox?.right ?: 0 } ?: 0
        val minQuantityX = quantityHeader?.boundingBox?.left ?: (maxRight * 55 / 100)

        // 2. Y축 높이 오차(45px)를 기준으로 전체 텍스트를 가로 행(Row) 단위로 그룹화
        val rows = mutableListOf<MutableList<Text.Element>>()
        val lineThreshold = 45
        val sortedElements = allElements.sortedBy { it.boundingBox?.top ?: 0 }

        for (element in sortedElements) {
            val box = element.boundingBox ?: continue
            val centerY = box.top + box.height() / 2

            val matchedRow = rows.find { row ->
                val rowCenterY = row.map { (it.boundingBox?.top ?: 0) + (it.boundingBox?.height() ?: 0) / 2 }.average()
                abs(centerY - rowCenterY) < lineThreshold
            }

            if (matchedRow != null) {
                matchedRow.add(element)
            } else {
                rows.add(mutableListOf(element))
            }
        }

        val processedMenus = mutableSetOf<String>()

        // 3. 각 행(Row)별로 검사 수행
        for (row in rows) {
            val sortedRow = row.sortedBy { it.boundingBox?.left ?: 0 }
            val fullRowText = sortedRow.joinToString("") { it.text }

            // 헤더 행("메뉴", "금액", "수량" 등이 포함된 줄)은 건너뜀
            if (fullRowText.contains("메뉴") && fullRowText.contains("금액")) continue

            // 현재 행에 등록된 메뉴 키워드가 포함되어 있는지 확인
            val matchedMenu = masterMenuList.find { menu ->
                menu.keywords.any { keyword -> fullRowText.replace("\\s".toRegex(), "").contains(keyword) }
            } ?: continue

            if (processedMenus.contains(matchedMenu.name)) continue

            // 4. 수량 열 영역(minQuantityX 이상인 좌표)에 속한 텍스트만 모아서 수량 파악
            val quantityElements = sortedRow.filter { (it.boundingBox?.left ?: 0) >= minQuantityX }
            val quantityText = quantityElements.joinToString("") { it.text }.trim()

            // 수량 열에 적힌 손글씨 해석
            val quantity = parseHandwrittenQuantity(quantityText)

            // 수량이 1개 이상 명시된 경우에만 정산 리스트에 추가
            if (quantity > 0) {
                val totalPrice = matchedMenu.price * quantity
                calculatedGrandTotal += totalPrice
                orderItemList.add(OrderItem(matchedMenu.name, matchedMenu.price, quantity, totalPrice))
                processedMenus.add(matchedMenu.name)
            }
        }

        if (orderItemList.isEmpty()) {
            showRetakeDialog("주문 수량이 표시된 메뉴를 찾지 못했습니다. 수량이 적힌 부분이 잘 보이도록 재촬영해 주세요.")
            return
        }

        val intent = Intent(this, ReceiptActivity::class.java).apply {
            putExtra("ORDER_ITEMS", ArrayList(orderItemList))
            putExtra("TOTAL_PRICE", calculatedGrandTotal)
        }
        startActivity(intent)
    }

    private fun parseHandwrittenQuantity(rawText: String): Int {
        val text = rawText.trim()
        if (text.isEmpty()) return 0

        // 1. 바를 정(正) 자
        if (text.contains("正")) return 5

        // 2. 수량 2개 표기 (T, t, r, Y, y, V, v, 7, ㅠ, ┬, ㄱ 등)
        if (text.contains("T", ignoreCase = true) ||
            text.contains("r", ignoreCase = true) ||
            text.contains("Y", ignoreCase = true) ||
            text.contains("V", ignoreCase = true) ||
            text.contains("7") ||
            text.contains("ㅠ") ||
            text.contains("┬") ||
            text.contains("ㄱ")) {
            return 2
        }

        // 3. 수량 1개 표기 (ㅡ, -, ~, 1, |, I, l, _, ─ 등)
        if (text.contains("ㅡ") || text.contains("-") || text.contains("~") ||
            text.contains("1") || text.contains("|") || text.contains("─") ||
            text.contains("I") || text.contains("l") || text.contains("_") ||
            text.contains("J", ignoreCase = true)) {
            return 1
        }

        // 4. 일반 아라비아 숫자 (1~9 사이)
        val digits = text.replace("[^0-9]".toRegex(), "")
        if (digits.isNotEmpty()) {
            val num = digits.toIntOrNull() ?: 0
            if (num in 1..9) return num
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
