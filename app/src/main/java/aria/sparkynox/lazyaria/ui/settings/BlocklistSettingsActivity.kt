package aria.sparkynox.lazyaria.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.browser.BlocklistUpdater
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class BlocklistSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocklist_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Ad Block Lists"

        refreshStatus()
        populateSources()

        findViewById<View>(R.id.btnForceUpdate).setOnClickListener {
            forceUpdate()
        }
    }

    private fun refreshStatus() {
        val rules = BlocklistUpdater.getRuleCount(this)
        val lastUpdate = BlocklistUpdater.getLastUpdateTime(this)
        val sdf = SimpleDateFormat("MMM d yyyy, HH:mm", Locale.getDefault())
        val dateStr = if (lastUpdate > 0)
            sdf.format(Date(lastUpdate * 1000))
        else "Never"

        findViewById<TextView>(R.id.txtRuleCount).text = "$rules rules loaded"
        findViewById<TextView>(R.id.txtLastUpdated).text = "Last updated: $dateStr"
    }

    private fun populateSources() {
        val container = findViewById<LinearLayout>(R.id.sourcesContainer)
        container.removeAllViews()

        BlocklistUpdater.BUILT_IN_SOURCES.forEach { source ->
            val row = layoutInflater.inflate(R.layout.item_blocklist_source, container, false)
            row.findViewById<TextView>(R.id.sourceName).text = source.name
            row.findViewById<TextView>(R.id.sourceUrl).text = source.url
            val toggle = row.findViewById<Switch>(R.id.sourceEnabled)
            toggle.isChecked = source.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                // Disable all others first (single-source for now)
                BlocklistUpdater.BUILT_IN_SOURCES.forEach { it.enabled = false }
                source.enabled = checked
                populateSources() // redraw
            }
            container.addView(row)
        }
    }

    private fun forceUpdate() {
        val btn = findViewById<Button>(R.id.btnForceUpdate)
        val progress = findViewById<ProgressBar>(R.id.updateProgress)
        val status = findViewById<TextView>(R.id.txtUpdateStatus)

        btn.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Fetching…"

        lifecycleScope.launch {
            val result = BlocklistUpdater.forceUpdate(this@BlocklistSettingsActivity)
            withContext(Dispatchers.Main) {
                btn.isEnabled = true
                progress.visibility = View.GONE
                if (result.error != null) {
                    status.text = "Error: ${result.error}"
                } else {
                    val changeStr = if (result.changed) "updated" else "no changes"
                    status.text = "${result.rules} rules, ${result.bloomKb}KB bloom — $changeStr"
                    refreshStatus()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }
}
