package com.example.orderscanner

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.orderscanner.databinding.ActivityReceiptBinding
import java.text.NumberFormat
import java.util.Locale

class ReceiptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val orderItems = intent.getSerializableExtra("ORDER_ITEMS") as? ArrayList<OrderItem> ?: arrayListOf()
        val totalPrice = intent.getIntExtra("TOTAL_PRICE", 0)

        displayReceipt(orderItems, totalPrice)

        binding.btnRetake.setOnClickListener {
            finish()
        }

        binding.btnConfirm.setOnClickListener {
            finish()
        }
    }

    private fun displayReceipt(items: List<OrderItem>, total: Int) {
        val formatter = NumberFormat.getNumberInstance(Locale.KOREA)
        val sb = StringBuilder()

        sb.append("=========================================\n")
        sb.append(String.format("%-14s %4s %12s\n", "메뉴명", "수량", "금액"))
        sb.append("-----------------------------------------\n")

        for (item in items) {
            val name = if (item.menuName.length > 8) item.menuName.substring(0, 8) else item.menuName
            val formattedItemTotal = formatter.format(item.totalPrice) + "원"
            sb.append(String.format("%-12s %3d개 %12s\n", name, item.quantity, formattedItemTotal))
        }

        sb.append("=========================================\n")

        binding.tvReceiptContent.text = sb.toString()
        binding.tvTotalPrice.text = "총 합계: ${formatter.format(total)}원"
    }
}
