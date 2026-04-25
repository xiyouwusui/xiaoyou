package cn.com.omnimind.bot.activity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogService

class QuickLogEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_log_entry)

        val editor = findViewById<EditText>(R.id.quick_log_editor)
        val cancelButton = findViewById<Button>(R.id.quick_log_cancel_button)
        val saveButton = findViewById<Button>(R.id.quick_log_save_button)

        cancelButton.setOnClickListener {
            finish()
        }

        saveButton.setOnClickListener {
            val content = editor.text?.toString().orEmpty().trim()
            if (content.isEmpty()) {
                editor.error = getString(R.string.quick_log_content_required)
                return@setOnClickListener
            }
            runCatching {
                QuickLogService(this).addLog(
                    content = content,
                    source = QuickLogService.SOURCE_WIDGET
                )
            }.onSuccess {
                Toast.makeText(
                    this,
                    getString(R.string.quick_log_save_success),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.quick_log_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
