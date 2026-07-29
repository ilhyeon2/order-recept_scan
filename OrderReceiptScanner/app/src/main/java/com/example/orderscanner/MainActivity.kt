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
                    parseOrderSheetAndValidate(visionText, bitmap)
                }
                .addOnFailureListener {
                    showRetakeDialog("텍스트 인식이 불가능합니다. 빛 반사가 없는 곳에서 재촬영해 주세요.")
                }
        } catch (e: Exception) {
            showRetakeDialog("이미지 처리 중 오류가 발생했습니다. 재촬영해 주세요.")
        }
    }

    private fun parseOrderSheetAndValidate(visionText: Text, originalBitmap: Bitmap) {
        val orderItemList = mutableListOf<OrderItem>()
        var calculatedGrandTotal = 0
        var writtenTotalInSheet = -1

        val textBlocks = visionText.textBlocks

        for (block in textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()

                if (lineText.contains("합계")) {
                    val digits = lineText.replace("[^0-9]".toRegex(), "")
                    if (digits.isNotEmpty()) {
                        writtenTotalInSheet = digits.toInt()
                    }
                    continue
                }

                val numbers = "[0-9]+,[0-9]+|[0-9]+".toRegex().findAll(lineText)
                    .map { it.value.replace(",", "").toInt() }
                    .filter { it >= 1000 }
                    .toList()

                if (numbers.isNotEmpty()) {
                    val unitPrice = numbers[0]
                    val quantity = extractQuantityWithJeongRules(line, originalBitmap)

                    if (unitPrice > 0 && quantity > 0) {
                        val itemTotal = unitPrice * quantity
                        calculatedGrandTotal += itemTotal

                        val rawMenuName = lineText.replace("[0-9]|,|원|\\s".toRegex(), "")
                        val menuName = if (rawMenuName.length >= 2) rawMenuName else "주문 메뉴"

                        orderItemList.add(OrderItem(menuName, unitPrice, quantity, itemTotal))
                    }
                }
            }
        }

        if (orderItemList.isEmpty()) {
            showRetakeDialog("인식된 주문 내역이 없습니다. 주문서 전체가 보이도록 재촬영해 주세요.")
            return
        }

        if (writtenTotalInSheet > 0 && writtenTotalInSheet != calculatedGrandTotal) {
            showRetakeDialog("주문서에 기재된 합계(${writtenTotalInSheet}원)와 계산된 금액(${calculatedGrandTotal}원)이 일치하지 않습니다. 다시 촬영해 주세요.")
            return
        }

        val intent = Intent(this, ReceiptActivity::class.java).apply {
            putExtra("ORDER_ITEMS", ArrayList(orderItemList))
            putExtra("TOTAL_PRICE", calculatedGrandTotal)
        }
        startActivity(intent)
    }

    private fun extractQuantityWithJeongRules(line: Text.Line, originalBitmap: Bitmap): Int {
        val lineText = line.text

        val textBasedQty = JeongStrokeCounter.parseTextToQuantity(lineText)
        if (textBasedQty > 1 || lineText.contains("T") || lineText.contains("正") || lineText.contains("ㅡ")) {
            return textBasedQty
        }

        val boundingBox = line.boundingBox
        if (boundingBox != null && boundingBox.width() > 0 && boundingBox.height() > 0) {
            try {
                val cropX = (boundingBox.left + boundingBox.width() * 0.7).toInt().coerceIn(0, originalBitmap.width - 1)
                val cropY = boundingBox.top.coerceIn(0, originalBitmap.height - 1)
                val cropW = (boundingBox.width() * 0.3).toInt().coerceAtMost(originalBitmap.width - cropX)
                val cropH = boundingBox.height().coerceAtMost(originalBitmap.height - cropY)

                if (cropW > 10 && cropH > 10) {
                    val croppedBitmap = Bitmap.createBitmap(originalBitmap, cropX, cropY, cropW, cropH)
                    val strokeCount = JeongStrokeCounter.countStrokesFromBitmap(croppedBitmap)
                    if (strokeCount > 0) return strokeCount
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return 1
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
