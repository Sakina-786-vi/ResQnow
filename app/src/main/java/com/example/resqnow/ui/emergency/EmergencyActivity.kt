package com.example.resqnow.ui.emergency

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.resqnow.R
import com.example.resqnow.data.repository.HighwayDatabase
import com.example.resqnow.databinding.ActivityEmergencyBinding
import com.example.resqnow.util.EmergencySmsHelper
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.launch

/**
 * Full-screen emergency activity.
 * - Auto-dials 112 if user doesn't cancel within 5 seconds
 * - Sends GPS SMS to 3 nearest emergency contacts
 * - Shows on lock screen
 */
class EmergencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmergencyBinding
    private val prefsManager by lazy { PreferencesManager(this) }
    private var countDownTimer: CountDownTimer? = null
    private var autoDialCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLocationDisplay()
        setupButtons()
        triggerVibration()
        startAutoDialCountdown()
    }

    private fun setupLocationDisplay() {
        val (lat, lon, hwPair) = prefsManager.getLastGpsSync()
        val (highway, km) = hwPair

        binding.tvEmergencyLocation.text = if (highway.isNotEmpty() && highway != "Local Road") {
            "$highway  ·  KM $km"
        } else {
            "GPS Locating..."
        }

        if (lat != 0.0 && lon != 0.0) {
            binding.tvEmergencyGps.text = String.format("%.5f, %.5f", lat, lon)
            binding.tvEmergencyMapsUrl.text = "maps.google.com/?q=$lat,$lon"
        }

        // Show nearest hospital
        if (lat != 0.0) {
            val nearest = HighwayDatabase.getNearestFacilities(lat, lon).firstOrNull()
            nearest?.let {
                binding.tvNearestHospitalEmergency.text =
                    "🏥 ${it.name} — ${String.format("%.1f", it.distanceKm)} km"
            }
        }
    }

    private fun setupButtons() {
        // CALL 112
        binding.btnCall112.setOnClickListener {
            countDownTimer?.cancel()
            autoDialCancelled = true
            call112()
        }

        // Send SOS SMS
        binding.btnSendSosEmail.setOnClickListener {
            countDownTimer?.cancel()
            sendEmergencySms()
        }

        // Open Maps
        binding.btnOpenMaps.setOnClickListener {
            val (lat, lon, _) = prefsManager.getLastGpsSync()
            startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:$lat,$lon?q=$lat,$lon(SOS+Location)"))
            )
        }

        // Cancel (dismiss screen)
        binding.btnCancelEmergency.setOnClickListener {
            countDownTimer?.cancel()
            autoDialCancelled = true
            binding.tvCountdown.text = "CANCELLED"
            binding.btnCancelEmergency.isEnabled = false
            finish()
        }
    }

    private fun startAutoDialCountdown() {
        binding.tvCountdown.text = "5"

        countDownTimer = object : CountDownTimer(5_000, 1_000) {
            override fun onTick(msLeft: Long) {
                val sec = (msLeft / 1000 + 1).toInt()
                binding.tvCountdown.text = "$sec"
            }
            override fun onFinish() {
                if (!autoDialCancelled) {
                    binding.tvCountdown.text = "📞"
                    call112()
                    sendEmergencySms()
                }
            }
        }.start()
    }

    private fun call112() {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:112"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }
    }

    private fun sendEmergencySms() {
        if (!EmergencySmsHelper.canSendSms(this)) {
            Toast.makeText(this, "SMS permission required for automatic sending", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val userContacts = EmergencySmsHelper.getUserEmergencyPhones(this@EmergencyActivity, prefsManager)
            if (userContacts.isEmpty()) {
                startActivity(Intent(this@EmergencyActivity, com.example.resqnow.ui.contacts.EmergencyContactsActivity::class.java))
                Toast.makeText(
                    this@EmergencyActivity,
                    "No saved contacts. Sending to default helpline.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            val destinations = EmergencySmsHelper.getSmsDestinations(this@EmergencyActivity, prefsManager)
            val message = EmergencySmsHelper.buildEmergencyMessage(prefsManager)
            val result = EmergencySmsHelper.sendEmergencySmsAutomatically(
                context = this@EmergencyActivity,
                phones = destinations,
                message = message
            )
            if (result.sentCount > 0) {
                Toast.makeText(
                    this@EmergencyActivity,
                    "✅ SOS sent to ${result.sentCount} contact(s)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@EmergencyActivity,
                    "Failed to send SMS (${result.failedCount} failed, ${result.invalidCount} invalid)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 300, 200, 300, 200, 300), -1
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
