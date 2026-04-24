package com.example.resqnow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.resqnow.data.repository.AppDatabase
import com.example.resqnow.ui.auth.RegistrationActivity
import com.example.resqnow.ui.contacts.EmergencyContactsActivity
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.ui.onboarding.OnboardingActivity
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class splash_screen : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash_screen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        lifecycleScope.launch {
            delay(3000)
            val prefs = PreferencesManager(this@splash_screen)
            val (hasRegisteredUser, hasSavedContacts) = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@splash_screen)
                val dao = db.userProfileDao()
                val contactsDao = db.emergencyContactDao()
                val savedId = prefs.getRegisteredUserId()
                val user = if (savedId > 0) dao.getById(savedId) else dao.getLatestUser()
                val userId = user?.id ?: -1L
                if (savedId <= 0 && userId > 0) prefs.saveRegisteredUserId(userId)

                val contactCount = if (userId > 0) contactsDao.getForUser(userId).count {
                    it.phone.filter(Char::isDigit).length >= 6
                } else {
                    0
                }
                (user?.isVerified == true) to (prefs.hasAnyEmergencyContact() || contactCount > 0)
            }

            val nextIntent = when {
                !hasRegisteredUser -> Intent(this@splash_screen, RegistrationActivity::class.java)
                !prefs.isOnboardingDone() -> Intent(this@splash_screen, OnboardingActivity::class.java)
                !hasSavedContacts ->
                    Intent(this@splash_screen, EmergencyContactsActivity::class.java)
                        .putExtra("setup_flow", true)
                else -> Intent(this@splash_screen, MainActivity::class.java)
            }

            startActivity(nextIntent)
            finish()
        }
    }
}
