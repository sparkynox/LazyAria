package aria.sparkynox.lazyaria.ui.settings

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import aria.sparkynox.lazyaria.R
import aria.sparkynox.lazyaria.core.RustCore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class RedirectRulesActivity : AppCompatActivity() {

    data class RedirectRule(val host: String, val blocked: Boolean)

    private val rules = mutableListOf<RedirectRule>()
    private lateinit var adapter: RulesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_redirect_rules)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Redirect Rules"

        val rv = findViewById<RecyclerView>(R.id.rulesList)
        adapter = RulesAdapter(rules,
            onToggle = { rule, nowBlocked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    RustCore.setRedirectRule(rule.host, nowBlocked)
                }
            },
            onDelete = { rule ->
                lifecycleScope.launch(Dispatchers.IO) {
                    RustCore.removeRedirectRule(rule.host)
                }
                rules.remove(rule)
                adapter.notifyDataSetChanged()
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnAddRule).setOnClickListener { showAddDialog() }
        loadRules()
    }

    private fun loadRules() {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { RustCore.getRedirectRulesJson() }
            val parsed: Map<String, List<String>> = Gson().fromJson(
                json, object : TypeToken<Map<String, List<String>>>() {}.type
            )
            rules.clear()
            parsed["blocked"]?.forEach { rules.add(RedirectRule(it, blocked = true)) }
            parsed["allowed"]?.forEach { rules.add(RedirectRule(it, blocked = false)) }
            rules.sortBy { it.host }
            adapter.notifyDataSetChanged()
        }
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_redirect_rule, null)
        val hostInput = view.findViewById<EditText>(R.id.ruleHost)
        val blockSwitch = view.findViewById<Switch>(R.id.ruleBlock)

        AlertDialog.Builder(this)
            .setTitle("Add redirect rule")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val host = hostInput.text.toString().trim().lowercase()
                if (host.isNotBlank()) {
                    val blocked = blockSwitch.isChecked
                    lifecycleScope.launch(Dispatchers.IO) {
                        RustCore.setRedirectRule(host, blocked)
                        withContext(Dispatchers.Main) {
                            rules.add(RedirectRule(host, blocked))
                            rules.sortBy { it.host }
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    class RulesAdapter(
        private val items: MutableList<RedirectRule>,
        private val onToggle: (RedirectRule, Boolean) -> Unit,
        private val onDelete: (RedirectRule) -> Unit,
    ) : RecyclerView.Adapter<RulesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val host: TextView   = v.findViewById(R.id.ruleHostText)
            val badge: TextView  = v.findViewById(R.id.ruleBadge)
            val toggle: Switch   = v.findViewById(R.id.ruleToggle)
            val delete: ImageButton = v.findViewById(R.id.ruleDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_redirect_rule, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val rule = items[pos]
            holder.host.text = rule.host
            holder.badge.text = if (rule.blocked) "BLOCK" else "ALLOW"
            holder.badge.setBackgroundResource(
                if (rule.blocked) R.drawable.badge_block else R.drawable.badge_allow
            )
            holder.toggle.isChecked = rule.blocked
            holder.toggle.setOnCheckedChangeListener { _, isChecked ->
                items[pos] = rule.copy(blocked = isChecked)
                onToggle(rule, isChecked)
                notifyItemChanged(pos)
            }
            holder.delete.setOnClickListener { onDelete(rule) }
        }

        override fun getItemCount() = items.size
    }
}
