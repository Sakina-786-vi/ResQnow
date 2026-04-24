package com.example.resqnow.ui.sos

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.resqnow.ui.contacts.EmergencyContactsActivity
import com.example.resqnow.util.EmergencySmsHelper
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.launch

/**
 * Entry point for the notification "SOS SMS" action.
 * Sends SOS SMS automatically to emergency contacts.
 */
class SendSosSmsActivity : ComponentActivity() {

    private val prefs by lazy { PreferencesManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!EmergencySmsHelper.canSendSms(this)) {
            Toast.makeText(this, "SMS permission required for automatic sending", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val userContacts = EmergencySmsHelper.getUserEmergencyPhones(this@SendSosSmsActivity, prefs)
            if (userContacts.isEmpty()) {
                startActivity(Intent(this@SendSosSmsActivity, EmergencyContactsActivity::class.java))
                Toast.makeText(
                    this@SendSosSmsActivity,
                    "No saved contacts. Sending to default helpline.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            val destinations = EmergencySmsHelper.getSmsDestinations(this@SendSosSmsActivity, prefs)
            val msg = EmergencySmsHelper.buildEmergencyMessage(prefs)
            val result = EmergencySmsHelper.sendEmergencySmsAutomatically(
                context = this@SendSosSmsActivity,
                phones = destinations,
                message = msg
            )
            if (result.sentCount > 0) {
                Toast.makeText(
                    this@SendSosSmsActivity,
                    "SOS sent to ${result.sentCount} contact(s)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@SendSosSmsActivity,
                    "Failed to send SMS (${result.failedCount} failed, ${result.invalidCount} invalid)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }
}
