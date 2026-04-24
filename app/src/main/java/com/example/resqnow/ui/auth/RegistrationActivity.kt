package com.example.resqnow.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.resqnow.data.model.UserProfile
import com.example.resqnow.data.repository.AppDatabase
import com.example.resqnow.databinding.ActivityRegistrationBinding
import com.example.resqnow.ui.contacts.EmergencyContactsActivity
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.ui.onboarding.OnboardingActivity
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegistrationActivity : ComponentActivity() {

    private lateinit var binding: ActivityRegistrationBinding
    private val prefs by lazy { PreferencesManager(this) }
    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnContinue.setOnClickListener { saveAndContinue() }
    }

    private fun saveAndContinue() {
        val name = binding.etFullName.text?.toString().orEmpty().trim()
        val phone = normalizePhone(binding.etPhone.text?.toString().orEmpty())

        if (name.length < 2) {
            binding.etFullName.error = "Enter your full name"
            return
        }
        if (phone == null) {
            binding.etPhone.error = "Enter a valid phone with country code (e.g. +919876543210)"
            return
        }
        val phoneE164 = phone

        binding.etFullName.error = null
        binding.etPhone.error = null
        setLoading(true)

        lifecycleScope.launch {
            val userId = withContext(Dispatchers.IO) {
                val existing = db.userProfileDao().getByPhone(phoneE164)
                val row = (existing ?: UserProfile(fullName = name, phoneE164 = phoneE164)).copy(
                    fullName = name,
                    isVerified = true,
                    verifiedAt = System.currentTimeMillis()
                )
                db.userProfileDao().upsert(row)
            }

            prefs.saveRegisteredUserId(userId)
            setLoading(false)
            Toast.makeText(this@RegistrationActivity, "Registration saved", Toast.LENGTH_SHORT).show()

            val next = when {
                !prefs.isOnboardingDone() -> Intent(this@RegistrationActivity, OnboardingActivity::class.java)
                !prefs.hasAnyEmergencyContact() -> Intent(this@RegistrationActivity, EmergencyContactsActivity::class.java)
                    .putExtra("setup_flow", true)
                else -> Intent(this@RegistrationActivity, MainActivity::class.java)
            }
            next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(next)
            finish()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnContinue.isEnabled = !loading
        binding.btnContinue.text = if (loading) "Saving..." else "Save & Continue"
    }

    private fun normalizePhone(raw: String): String? {
        val cleaned = raw.filter { it.isDigit() || it == '+' }
        if (cleaned.startsWith("+")) {
            val digits = cleaned.drop(1)
            return if (digits.length in 8..15 && digits.all { it.isDigit() }) "+$digits" else null
        }

        val digitsOnly = cleaned.filter { it.isDigit() }
        return when {
            digitsOnly.length in 8..15 -> "+$digitsOnly"
            digitsOnly.length == 10 -> "+91$digitsOnly"
            else -> null
        }
    }
}
