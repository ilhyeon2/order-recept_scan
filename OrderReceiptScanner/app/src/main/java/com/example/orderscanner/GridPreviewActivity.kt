package com.example.orderscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.orderscanner.databinding.ActivityGridPreviewBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.Serializable
import kotlin.math.abs

// 공통 데이터 클래스 정의
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

class GridPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGridPreviewBinding
    private val orderItemList = mutableListOf<OrderItem>()
    private var calculatedGrandTotal = 0

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUriString = intent.getStringExtra("IMAGE_URI")
        if (imageUriString == null) {
            Toast.makeText(this, "이미지 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val imageUri = Uri.parse(imageUriString)
        processImageAndDrawGrid(imageUri)

        // 그리드 확인 화면 내의 [다음] 버튼 클릭 시 정산 화면으로 이동
        binding.btnProceedToReceipt.setOnClickListener {
            if (orderItemList.isEmpty()) {
                Toast.makeText(this, "인식된 주문 항목이 없습니다. 다시 촬영해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, ReceiptActivity::class.java).apply {
                putExtra("ORDER_ITEMS", ArrayList(orderItemList))
                putExtra("TOTAL_PRICE", calculatedGrandTotal)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun processImageAndDrawGrid(imageUri: Uri) {
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
                    analyzeTextAndBuildGrid(bitmap, visionText)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "텍스트 인식 실패", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "이미지 처리 오류", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeTextAndBuildGrid(bitmap: Bitmap, visionText: Text) {
        val allElements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        if (allElements.isEmpty()) return

        val priceHeader = allElements.find { it.text.contains("금액") }
        val qtyHeader = allElements.find { it.text.contains("수량") }

        val maxRight = allElements.maxOfOrNull { it.boundingBox?.right ?: 0 } ?: bitmap.width
        val col1Boundary = priceHeader?.boundingBox?.left ?: (maxRight * 0.4).toInt()

        val col2Boundary = if (qtyHeader?.boundingBox != null) {
            (qtyHeader.boundingBox!!.left - 50).coerceAtLeast(col1Boundary + 50)
        } else {
            (maxRight * 0.62).toInt()
        }

        // [개선] 고해상도 이미지 크기에 비례하여 선 두께를 동적으로 계산 (선이 안 보이던 문제 해결)
        val scaleFactor = (bitmap.width.toFloat() / 1000f).coerceAtLeast(1f)
        val dynamicBoxWidth = (2f * scaleFactor).coerceAtLeast(2f)
        val dynamicColWidth = (6f * scaleFactor).coerceAtLeast(4f)

        val debugBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)
        val paintBox = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = dynamicBoxWidth }
        val paintCol = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = dynamicColWidth }

        for (element in allElements) {
            element.boundingBox?.let { canvas.drawRect(it, paintBox) }
        }
        canvas.drawLine(col1Boundary.toFloat(), 0f, col1Boundary.toFloat(), debugBitmap.height.toFloat(), paintCol)
        canvas.drawLine(col2Boundary.toFloat(), 0f, col2Boundary.toFloat(), debugBitmap.height.toFloat(), paintCol)

        binding.ivGridPreview.setImageBitmap(debugBitmap)

        // 행 분할 및 수량 파악 로직 수행
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

        for (row in rows) {
            val menuCellElements = row.filter { (it.boundingBox?.centerX() ?: 0) < col1Boundary }
            val qtyCellElements = row.filter { (it.boundingBox?.centerX() ?: 0) > col2Boundary }

            val rowMenuText = menuCellElements.joinToString("") { it.text }.replace("\\s".toRegex(), "")
            if (rowMenuText.contains("메뉴")) continue

            val matchedMenu = masterMenuList.find { menu ->
                menu.keywords.any { keyword -> rowMenuText.contains(keyword) }
            } ?: continue

            if (processedMenus.contains(matchedMenu.name)) continue

            val qtyRawText = qtyCellElements.joinToString("") { it.text }
            
            // [개선] 기존 단순 문자열 파싱 대신 고성능 JeongStrokeCounter 연동하여 인식률 대폭 향상[cite: 4]
            val quantity = JeongStrokeCounter.parseTextToQuantity(qtyRawText)

            if (quantity > 0) {
                val itemTotal = matchedMenu.price * quantity
                calculatedGrandTotal += itemTotal
                orderItemList.add(OrderItem(matchedMenu.name, matchedMenu.price, quantity, itemTotal))
                processedMenus.add(matchedMenu.name)
            }
        }
    }
}
