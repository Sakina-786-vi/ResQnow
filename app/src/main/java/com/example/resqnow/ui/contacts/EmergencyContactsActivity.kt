package com.example.resqnow.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.resqnow.data.model.EmergencyContact
import com.example.resqnow.data.repository.AppDatabase
import com.example.resqnow.databinding.ActivityEmergencyContactsBinding
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmergencyContactsActivity : ComponentActivity() {

    private lateinit var binding: ActivityEmergencyContactsBinding
    private val prefs by lazy { PreferencesManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { preloadContacts() }

        binding.btnSaveContacts.setOnClickListener {
            lifecycleScope.launch { saveContacts() }
        }
    }

    private suspend fun preloadContacts() {
        val userId = prefs.getRegisteredUserId()
        val existing = if (userId > 0) {
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@EmergencyContactsActivity)
                    .emergencyContactDao()
                    .getForUser(userId)
                    .map { it.contactName to it.phone }
            }
        } else {
            prefs.getEmergencyContacts()
        }

        fun setRow(i: Int, name: String, phone: String) {
            when (i) {
                0 -> {
                    binding.etName1.setText(name)
                    binding.etPhone1.setText(phone)
                }
                1 -> {
                    binding.etName2.setText(name)
                    binding.etPhone2.setText(phone)
                }
                2 -> {
                    binding.etName3.setText(name)
                    binding.etPhone3.setText(phone)
                }
                3 -> {
                    binding.etName4.setText(name)
                    binding.etPhone4.setText(phone)
                }
            }
        }

        existing.forEachIndexed { i, (name, phone) -> setRow(i, name, phone) }
    }

    private suspend fun saveContacts() {
        val contacts = listOf(
            binding.etName1.text?.toString().orEmpty() to binding.etPhone1.text?.toString().orEmpty(),
            binding.etName2.text?.toString().orEmpty() to binding.etPhone2.text?.toString().orEmpty(),
            binding.etName3.text?.toString().orEmpty() to binding.etPhone3.text?.toString().orEmpty(),
            binding.etName4.text?.toString().orEmpty() to binding.etPhone4.text?.toString().orEmpty(),
        )

        prefs.saveEmergencyContacts(contacts)

        val userId = prefs.getRegisteredUserId()
        if (userId > 0) {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@EmergencyContactsActivity).emergencyContactDao()
                dao.deleteForUser(userId)
                val rows = contacts.mapIndexed { index, (name, phone) ->
                    EmergencyContact(
                        userId = userId,
                        slotIndex = index,
                        contactName = name.trim(),
                        phone = phone.trim()
                    )
                }
                dao.insertAll(rows)
            }
        }

        if (!prefs.hasAnyEmergencyContact()) {
            Toast.makeText(
                this,
                "Saved. Add at least 1 urgent contact — default helpline still used.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Emergency contacts saved.", Toast.LENGTH_SHORT).show()
        }

        if (intent.getBooleanExtra("setup_flow", false)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
    }
}
