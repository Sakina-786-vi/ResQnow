package com.example.resqnow.ui.home

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.example.resqnow.R
import com.example.resqnow.data.model.EmergencyFacility
import com.example.resqnow.data.model.FacilityType
import com.example.resqnow.databinding.ActivityMain2Binding
import com.example.resqnow.ui.emergency.EmergencyActivity
import com.example.resqnow.ui.onboarding.OnboardingActivity
import com.example.resqnow.util.EmergencySmsHelper
import com.example.resqnow.util.PreferencesManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()
    private val prefsManager by lazy { PreferencesManager(this) }
    private var pulseAnimator: ObjectAnimator? = null

    // ─── PERMISSIONS ──────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val smsGranted      = results[Manifest.permission.SEND_SMS] == true
        val callGranted     = results[Manifest.permission.CALL_PHONE] == true

        when {
            locationGranted -> {
                showSnackbar("✅ Location granted — Highway SOS is ready!")
                if (smsGranted && callGranted) {
                    showSnackbar("✅ All permissions granted. Full emergency features active.")
                }
            }
            else -> showSnackbar(
                "⚠️ Location required for Highway SOS to work.",
                "GRANT"
            ) { requestPermissions() }
        }
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        checkOnboarding()
        setupUI()
        setupObservers()
        checkPermissions()
        initRealtimeMessaging()

        // If launched from tile needing permissions
        if (intent.getBooleanExtra("request_permissions", false)) {
            requestPermissions()
        }
    }

    private fun checkOnboarding() {
        lifecycleScope.launch {
            val done = prefsManager.isOnboardingDone()
            if (!done) {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            }
        }
    }

    // ─── UI SETUP ─────────────────────────────────────────────────────

    private fun setupUI() {
        // Big SOS button
        binding.btnEmergency.setOnClickListener { handleEmergencyTap() }
        binding.btnEmergency.setOnLongClickListener { call112(); true }

        // Action buttons
        binding.btnCallHospital.setOnClickListener { showNearestHospitals() }
        binding.btnCallPolice.setOnClickListener   { dialPolice() }
        binding.btnSendSms.setOnClickListener      { triggerEmergencySms() }

        // Toggle tracking
        binding.btnToggleTracking.setOnClickListener { toggleTracking() }

        // Tile setup instruction
        binding.cardTileSetup.setOnClickListener { showTileInstructions() }

        // Share app via WhatsApp
        binding.btnShareApp.setOnClickListener { shareAppViaWhatsApp() }

        // Make the "nearby" text hints clickable too.
        binding.tvNearestHospital.setOnClickListener { showNearestHospitals() }
        binding.tvNearestPolice.setOnClickListener { dialPolice() }
    }

    private fun setupObservers() {
        // GPS state updates
        viewModel.gpsState.observe(this) { state ->
            binding.tvLatitude.text      = String.format("%.6f°", state.latitude)
            binding.tvLongitude.text     = String.format("%.6f°", state.longitude)
            binding.tvHighwayName.text   = state.highwayName.ifEmpty { "Detecting..." }
            binding.tvKmMarker.text      = if (state.kmMarker > 0) "KM ${state.kmMarker}" else "KM ---"
            binding.tvVehicleCount.text  = "${state.vehicleCount}"
            binding.tvSpeedKmh.text      = String.format("%.0f km/h", state.speed * 3.6f)
            binding.tvAccuracy.text      = String.format("±%.0fm", state.accuracy)

            // Dynamic "nearby" hints: open Google Maps based on the user's current GPS fix.
            if (state.latitude != 0.0 && state.longitude != 0.0) {
                binding.tvNearestHospital.text = "🏥 Tap for nearby hospitals"
                binding.tvNearestPolice.text = "👮 Tap for nearby police"
            } else {
                binding.tvNearestHospital.text = "🏥 Waiting for GPS..."
                binding.tvNearestPolice.text = "👮 Waiting for GPS..."
            }
        }

        // Tracking state
        viewModel.isTracking.observe(this) { tracking ->
            updateTrackingUI(tracking)
        }

        // Last update time
        viewModel.lastUpdateTime.observe(this) { time ->
            binding.tvLastUpdate.text = "Updated: $time"
        }

        // Rumor detection status
        viewModel.rumorStatus.observe(this) { status ->
            when (status) {
                is RumorStatus.NoData -> {
                    binding.tvRumorStatus.text = "Waiting for GPS data..."
                    binding.tvRumorStatus.setTextColor(getColor(R.color.text_secondary))
                    binding.ivRumorIcon.setImageResource(R.drawable.ic_radar)
                    binding.cardRumorStatus.setCardBackgroundColor(getColor(R.color.card_bg))
                }
                is RumorStatus.Monitoring -> {
                    binding.tvRumorStatus.text = "📡 Monitoring — ${status.count} vehicles in this zone"
                    binding.tvRumorStatus.setTextColor(getColor(R.color.accent_orange))
                    binding.ivRumorIcon.setImageResource(R.drawable.ic_radar)
                    binding.cardRumorStatus.setCardBackgroundColor(getColor(R.color.card_bg))
                }
                is RumorStatus.Debunked -> {
                    binding.tvRumorStatus.text =
                        "✅ ROAD CLEAR — ${status.count} vehicles confirmed passing ${status.highway} KM${status.km}"
                    binding.tvRumorStatus.setTextColor(getColor(R.color.green_active))
                    binding.ivRumorIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.cardRumorStatus.setCardBackgroundColor(getColor(R.color.green_dim))
                    animateRumorDebunked()
                }
                is RumorStatus.Alert -> {
                    binding.tvRumorStatus.text = "⚠️ ${status.message}"
                    binding.tvRumorStatus.setTextColor(getColor(R.color.red_alert))
                }
            }
        }

        // Nearby facilities
        viewModel.nearbyFacilities.observe(this) { facilities ->
            updateFacilitiesUI(facilities)
        }
    }

    // ─── TRACKING ─────────────────────────────────────────────────────

    private fun toggleTracking() {
        if (viewModel.isTracking.value == true) {
            viewModel.stopTracking(this)
            stopPulseAnimation()
        } else {
            if (!hasLocationPermission()) {
                requestPermissions()
                return
            }
            viewModel.startTracking(this)
            startPulseAnimation()
        }
    }

    private fun updateTrackingUI(isTracking: Boolean) {
        if (isTracking) {
            binding.btnToggleTracking.text = "⏹  STOP TRACKING"
            binding.btnToggleTracking.setBackgroundResource(R.drawable.btn_stop_bg)
            binding.statusDot.setBackgroundResource(R.drawable.dot_green)
            binding.tvTrackingStatus.text = "● TRACKING ACTIVE"
            binding.tvTrackingStatus.setTextColor(getColor(R.color.green_active))
            startPulseAnimation()
        } else {
            binding.btnToggleTracking.text = "▶  START TRACKING"
            binding.btnToggleTracking.setBackgroundResource(R.drawable.btn_start_bg)
            binding.statusDot.setBackgroundResource(R.drawable.dot_red)
            binding.tvTrackingStatus.text = "○ TRACKING OFF"
            binding.tvTrackingStatus.setTextColor(getColor(R.color.text_secondary))
            stopPulseAnimation()
        }
    }

    // ─── EMERGENCY ACTIONS ────────────────────────────────────────────

    private fun handleEmergencyTap() {
        startActivity(Intent(this, EmergencyActivity::class.java))
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

    private fun dialPolice() {
        // Direct call if permission granted, otherwise fall back to dialer.
        val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:100"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            startActivity(call)
        } else {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:100")))
        }
    }

    private fun showNearestHospitals() {
        // "Easy + non-static": open Google Maps search near the current GPS fix.
        val gps = viewModel.gpsState.value
        if (gps == null) {
            showSnackbar("Enable GPS tracking first to find nearby hospitals")
            return
        }
        val uri = Uri.parse("geo:${gps.latitude},${gps.longitude}?q=hospital")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun triggerEmergencySms() {
        val gps = viewModel.gpsState.value
        if (gps == null) {
            showSnackbar("Enable GPS tracking to send location in SMS")
            return
        }

        if (!EmergencySmsHelper.canSendSms(this)) {
            showSnackbar("SMS permission required for automatic sending", "GRANT") { requestPermissions() }
            return
        }

        lifecycleScope.launch {
            val userContacts = EmergencySmsHelper.getUserEmergencyPhones(this@MainActivity, prefsManager)
            if (userContacts.isEmpty()) {
                startActivity(Intent(this@MainActivity, com.example.resqnow.ui.contacts.EmergencyContactsActivity::class.java))
                showSnackbar("No saved contacts. Sending to default helpline.")
            }

            val destinations = EmergencySmsHelper.getSmsDestinations(this@MainActivity, prefsManager)
            val message = EmergencySmsHelper.buildEmergencyMessage(prefsManager)
            val result = EmergencySmsHelper.sendEmergencySmsAutomatically(
                context = this@MainActivity,
                phones = destinations,
                message = message
            )
            if (result.sentCount > 0) {
                showSnackbar("✅ SMS sent to ${result.sentCount} contact(s)")
            } else {
                showSnackbar("Failed to send SMS (${result.failedCount} failed, ${result.invalidCount} invalid)")
            }
        }
    }

    // ─── FACILITIES UI ────────────────────────────────────────────────

    private fun updateFacilitiesUI(facilities: List<EmergencyFacility>) {
        if (facilities.isEmpty()) return

        val nearest = facilities.firstOrNull { it.type == FacilityType.HOSPITAL }
        val police  = facilities.firstOrNull {
            it.type == FacilityType.POLICE || it.type == FacilityType.HIGHWAY_PATROL
        }

        nearest?.let { f ->
            binding.tvNearestHospital.text =
                "🏥 ${f.name} — ${String.format("%.1f", f.distanceKm)} km"
        }
        police?.let { f ->
            binding.tvNearestPolice.text =
                "👮 ${f.name} — ${String.format("%.1f", f.distanceKm)} km"
        }
    }

    // ─── ANIMATIONS ───────────────────────────────────────────────────

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.pulseRing, View.ALPHA, 1f, 0f).apply {
            duration    = 1200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        binding.pulseRing.visibility = View.VISIBLE
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        binding.pulseRing.visibility = View.INVISIBLE
    }

    private fun animateRumorDebunked() {
        binding.cardRumorStatus.animate()
            .scaleX(1.03f).scaleY(1.03f).setDuration(150)
            .withEndAction {
                binding.cardRumorStatus.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
    }

    // ─── TILE SETUP ───────────────────────────────────────────────────

    private fun showTileInstructions() {
        AlertDialog.Builder(this, R.style.HighwaySOS_Dialog)
            .setTitle("⚡ Add Quick Settings Tile")
            .setMessage(
                "Get 1-tap emergency response:\n\n" +
                "1️⃣  Swipe down twice from top\n" +
                "2️⃣  Tap the ✏️ Edit button\n" +
                "3️⃣  Find 'Highway SOS' tile\n" +
                "4️⃣  Drag it to Quick Settings\n\n" +
                "Now swipe → tap → 112 called + GPS shared automatically!"
            )
            .setPositiveButton("GOT IT ✅", null)
            .show()
    }

    // ─── SHARE ────────────────────────────────────────────────────────

    private fun shareAppViaWhatsApp() {
        val shareText = "🛣️ Install Highway SOS — 1-tap emergency + rumor debunker for Indian highways!\n" +
                "10-sec install: https://play.google.com/store/apps/details?id=com.example.resqnow\n" +
                "Swipe down → tap tile → GPS shared. Saves lives. 🚨"
        try {
            startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type    = "text/plain"
                    `package` = "com.whatsapp"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
            )
        } catch (e: Exception) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }, "Share via"
            ))
        }
    }

    // ─── PERMISSIONS ──────────────────────────────────────────────────

    private fun checkPermissions() {
        if (!hasLocationPermission()) requestPermissions()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.POST_NOTIFICATIONS
        ))
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // ─── REAL-TIME MESSAGING (FCM) ────────────────────────────────────

    private fun initRealtimeMessaging() {
        // Keep main screen usable even when Firebase is not configured in local/debug builds.
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w(TAG, "Firebase not initialized; skipping FCM setup.")
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "FCM token fetch failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                prefsManager.saveFcmToken(token)
                Log.d(TAG, "FCM token: $token")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "FCM unavailable; continuing without push.", e)
        }
    }

    // ─── UTILS ────────────────────────────────────────────────────────

    private fun showSnackbar(msg: String, action: String? = null, cb: (() -> Unit)? = null) {
        val snack = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
        if (action != null && cb != null) snack.setAction(action) { cb() }
        snack.show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
