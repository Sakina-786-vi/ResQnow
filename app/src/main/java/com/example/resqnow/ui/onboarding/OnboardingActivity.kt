package com.example.resqnow.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.example.resqnow.R
import com.example.resqnow.databinding.ActivityOnboardingBinding
import com.example.resqnow.ui.contacts.EmergencyContactsActivity
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.launch

data class OnboardingPage(
    val iconRes: Int,
    val title: String,
    val subtitle: String,
    val description: String,
    val bgColorRes: Int
)

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val prefsManager by lazy { PreferencesManager(this) }

    private val pages = listOf(
        OnboardingPage(
            iconRes     = R.drawable.ic_sos_hero,
            title       = "Highway SOS",
            subtitle    = "1-Tap Emergency Response",
            description = "Swipe down, tap the tile. Your GPS is instantly shared with nearest hospitals and police — before you even speak.",
            bgColorRes  = R.color.onboard_red
        ),
        OnboardingPage(
            iconRes     = R.drawable.ic_grid_hero,
            title       = "Rumor Killer",
            subtitle    = "47 Phones Can't Be Wrong",
            description = "Our 500m highway grid counts passing vehicles in real time. 47 cars through NH44 KM152? That bridge is NOT collapsed. Panic stopped.",
            bgColorRes  = R.color.onboard_dark
        ),
        OnboardingPage(
            iconRes     = R.drawable.ic_battery_hero,
            title       = "Always On",
            subtitle    = "Just 1% Battery. All Day.",
            description = "GPS updates every 30 seconds. No camera. No ML. No heavy compute. Just pure GPS math — lightweight enough to run all day on any phone.",
            bgColorRes  = R.color.onboard_dark2
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupButtons()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = OnboardingAdapter(pages)

        TabLayoutMediator(binding.tabDots, binding.viewPager) { _, _ -> }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val isLast = position == pages.size - 1
                binding.btnNext.text = if (isLast) "GET STARTED →" else "NEXT →"
                binding.btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
            }
        })
    }

    private fun setupButtons() {
        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.size - 1) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            prefsManager.setOnboardingDone()
            val next = if (!prefsManager.hasAnyEmergencyContact()) {
                Intent(this@OnboardingActivity, EmergencyContactsActivity::class.java)
                    .putExtra("setup_flow", true)
            } else {
                Intent(this@OnboardingActivity, MainActivity::class.java)
            }
            next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(next)
            finish()
        }
    }
}

class OnboardingAdapter(private val pages: List<OnboardingPage>) :
    RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView  = view.findViewById(R.id.iv_onboard_icon)
        val title: TextView  = view.findViewById(R.id.tv_onboard_title)
        val sub: TextView    = view.findViewById(R.id.tv_onboard_sub)
        val desc: TextView   = view.findViewById(R.id.tv_onboard_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        holder.icon.setImageResource(page.iconRes)
        holder.title.text = page.title
        holder.sub.text   = page.subtitle
        holder.desc.text  = page.description
    }

    override fun getItemCount() = pages.size
}
