package aria.sparkynox.lazyaria.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import aria.sparkynox.lazyaria.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("blocklist_manager")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), BlocklistSettingsActivity::class.java))
                true
            }
            findPreference<Preference>("redirect_rules")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), RedirectRulesActivity::class.java))
                true
            }
        }
    }
}
