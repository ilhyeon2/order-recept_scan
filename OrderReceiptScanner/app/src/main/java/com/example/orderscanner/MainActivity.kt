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

    // 문서 스캐너 옵션 (자동 외곽선 감지 및 이미지 평면 보정 적용)
    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(false)
        .setPageLimit(1)
        .setResultFormats(RESULT_FORMAT_JPEG)
        .setScannerMode(SCANNER_MODE_FULL) 
        .build()

    private val scanner by lazy { GmsDocumentScanning.getClient(scannerOptions) }

    // 스캐너 실행 결과 처리기 (중복 선언 제거 완료)
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
                    parseOrderSheetWithDynamicGrid(visionText)
                }
                .addOnFailureListener {
                    showRetakeDialog("텍스트 인식이 불가능합니다. 빛 반사가 없는 곳에서 재촬영해 주세요.")
                }
        } catch (e: Exception) {
            showRetakeDialog("이미지 처리 중 오류가 발생했습니다. 재촬영해 주세요.")
        }
    }

    // [핵심 로직 1] 동적 가상 그리드 분할 및 정산 프로세서
    private fun parseOrderSheetWithDynamicGrid(visionText: Text) {
        val orderItemList = mutableListOf<OrderItem>()
        var calculatedGrandTotal = 0

        val allElements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        if (allElements.isEmpty()) {
            showRetakeDialog("인식된 텍스트가 없습니다. 다시 촬영해 주세요.")
            return
        }

        // 1. [동적 세로 열(Column) 경계선 설정] - '금액'과 '수량' 헤더 위치 파악
        val priceHeader = allElements.find { it.text.contains("금액") }
        val qtyHeader = allElements.find { it.text.contains("수량") }

        val maxRight = allElements.maxOfOrNull { it.boundingBox?.right ?: 0 } ?: 1000
        val col1Boundary = priceHeader?.boundingBox?.left ?: (maxRight * 0.4).toInt() // 메뉴와 금액 사이 구분선
        val col2Boundary = qtyHeader?.boundingBox?.left ?: (maxRight * 0.7).toInt()  // 금액과 수량 사이 구분선

        // 2. [동적 가로 행(Row) 그룹화] - 해상도에 구애받지 않고 글자 높이로 같은 줄 판별
        val rows = mutableListOf<MutableList<Text.Element>>()
        val sortedElements = allElements.sortedBy { it.boundingBox?.top ?: 0 }

        for (element in sortedElements) {
            val box = element.boundingBox ?: continue
            val centerY = box.top + box.height() / 2
            val dynamicThreshold = (box.height() * 0.7).coerceAtLeast(30.0)

            val matchedRow = rows.find { row ->
                val rowCenterY = row.map { (it.boundingBox?.top ?: 0) + (it.boundingBox?.height() ?: 0) / 2 }.average()
                abs(centerY - rowCenterY) < dynamicThreshold
            }

            if (matchedRow != null) {
                matchedRow.add(element)
            } else {
                rows.add(mutableListOf(element))
            }
        }

        val processedMenus = mutableSetOf<String>()

        // 3. [그리드 셀 분석 및 수량 판독]
        for (row in rows) {
            // 열 경계선을 기준으로 셀 데이터 분리
            val menuCellElements = row.filter { (it.boundingBox?.centerX() ?: 0) < col1Boundary }
            val qtyCellElements = row.filter { (it.boundingBox?.centerX() ?: 0) > col2Boundary }

            val rowMenuText = menuCellElements.joinToString("") { it.text }.replace("\\s".toRegex(), "")
            if (rowMenuText.contains("메뉴")) continue

            val matchedMenu = masterMenuList.find { menu ->
                menu.keywords.any { keyword -> rowMenuText.contains(keyword) }
            } ?: continue

            // 중복 계산 방지
            if (processedMenus.contains(matchedMenu.name)) continue

            // 수량 셀 영역의 텍스트를 파싱 알고리즘으로 넘김
            val qtyRawText = qtyCellElements.joinToString("") { it.text }
            val quantity = parseTallyStrokes(qtyRawText)

            if (quantity > 0) {
                val itemTotal = matchedMenu.price * quantity
                calculatedGrandTotal += itemTotal
                orderItemList.add(OrderItem(matchedMenu.name, matchedMenu.price, quantity, itemTotal))
                processedMenus.add(matchedMenu.name)
            }
        }

        if (orderItemList.isEmpty()) {
            showRetakeDialog("주문 수량이 표시된 메뉴를 찾지 못했습니다. 수량이 적힌 부분이 잘 보이도록 재촬영해 주세요.")
            return
        }

        // 결과 화면으로 데이터 전달 및 이동
        val intent = Intent(this, ReceiptActivity::class.java).apply {
            putExtra("ORDER_ITEMS", ArrayList(orderItemList))
            putExtra("TOTAL_PRICE", calculatedGrandTotal)
        }
        startActivity(intent)
    }

    // [핵심 로직 2] '바를 정(正)' 획수 인식 및 조합 치환 (대량 주문対応)
    private fun parseTallyStrokes(rawText: String): Int {
        var text = rawText.replace("\\s".toRegex(), "")
        if (text.isEmpty()) return 0

        // 1. 숫자로만 직접 기입된 경우 빠른 리턴
        val pureDigits = text.replace("[^0-9]".toRegex(), "")
        if (pureDigits.isNotEmpty() && text.length == pureDigits.length) {
            val num = pureDigits.toIntOrNull() ?: 0
            if (num in 1..99) return num
        }

        // 2. 완성된 '正' 자 개수 세기 및 분리
        val fullZhengCount = text.count { it == '正' }
        text = text.replace("正", "")

        // 3. '正' 자를 제외한 나머지 획수 분석
        val remainderStrokes = parseSingleTallyPattern(text)

        val total = (fullZhengCount * 5) + remainderStrokes
        if (total > 0) return total

        // 4. 예외 방어: 특수문자와 숫자가 섞여서 들어온 경우 숫자만 추출
        if (pureDigits.isNotEmpty()) {
            val num = pureDigits.toIntOrNull() ?: 0
            if (num in 1..99) return num
        }

        return 0
    }

    // [핵심 로직 3] 이미지(image_2ad4fc.png) 맞춤형 단일 획수 패턴 매칭
    private fun parseSingleTallyPattern(text: String): Int {
        if (text.isEmpty()) return 0
        val upperText = text.uppercase()

        // [4획]: 이미지 기준 -> iF, |F, IF, 王 (임금 왕), E
        if (upperText.contains("IF") || upperText.contains("|F") || 
            upperText.contains("王") || upperText.contains("E")) {
            return 4
        }

        // [3획]: 이미지 기준 -> 下 (아래 하), ㅠ
        if (upperText.contains("下") || upperText.contains("ㅠ")) {
            return 3
        }

        // [2획]: 이미지 기준 -> T, 丅(아래 하 이체자), ㅜ, ┬, ㄱ
        if (upperText.contains("T") || upperText.contains("丅") ||
            upperText.contains("┬") || upperText.contains("ㅜ") ||
            upperText.contains("ㄱ")) {
            return 2
        }

        // [1획]: 이미지 기준 -> 一 (한자 일), -, ㅡ, 1, _, ~
        if (upperText.contains("一") || upperText.contains("-") ||
            upperText.contains("ㅡ") || upperText.contains("1") ||
            upperText.contains("_") || upperText.contains("~")) {
            return 1
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
