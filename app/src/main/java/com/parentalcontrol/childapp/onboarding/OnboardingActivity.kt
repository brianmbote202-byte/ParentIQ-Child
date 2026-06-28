package com.parentalcontrol.childapp.onboarding

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.parentalcontrol.childapp.R

class OnboardingActivityV5 : AppCompatActivity(), PermissionFragment.OnPermissionCheckListener {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: MaterialButton
    private lateinit var btnBack: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var adapter: PermissionPagerAdapter

    private var currentPage = 0
    private val permissionScreens = mutableListOf<PermissionFragment>()

    // Track which permission screens have been granted
    private val grantedMap = mutableMapOf<Int, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // --- Initialize views ---
        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)
        progressIndicator = findViewById(R.id.progressIndicator)

        // --- Setup fragments ---
        setupPermissionScreens()
        adapter = PermissionPagerAdapter(this, permissionScreens)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false // disable swipe

        updateButtons()
        updateNextButtonState()

        // --- Next Button ---
        btnNext.setOnClickListener {
            if (currentPage < permissionScreens.size - 1) {
                currentPage++
                viewPager.currentItem = currentPage
                updateButtons()
                updateNextButtonState()
            } else finishOnboarding()
        }

        // --- Back Button ---
        btnBack.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                viewPager.currentItem = currentPage
                updateButtons()
                updateNextButtonState()
            }
        }

        // --- OnPageChange ---
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateButtons()
                updateNextButtonState()
            }
        })

        // --- Attach listeners to fragments ---
        permissionScreens.forEach { fragment ->
            fragment.setListener(this)
        }
    }

    private fun setupPermissionScreens() {
        val accessibilitySteps = listOf(
            "1. Open Settings",
            "2. Go to Accessibility",
            "3. Select Child App and turn it ON"
        )

        val samsungFlow = if (Build.MANUFACTURER.equals("samsung", true)) {
            listOf(
                "Step 1: Tap Installed Apps",
                "Step 2: Tap 'ChildApp Controlled Restricted Settings' TWICE",
                "Step 3: Press BACK → Tap 3 dots → Allow Restricted Settings",
                "Step 4: Press BACK → Tap Installed Apps again",
                "Step 5: Toggle ChildApp ON"
            )
        } else null

        permissionScreens.add(
            PermissionFragment(
                iconRes = R.drawable.ic_permission_placeholder,
                titleText = "Enable Accessibility",
                descriptionText = "To monitor and protect your child, please enable Accessibility Service.",
                steps = accessibilitySteps,
                samsungFlow = samsungFlow
            )
        )

        permissionScreens.add(
            PermissionFragment(
                iconRes = R.drawable.ic_permission_location,
                titleText = "Enable Location",
                descriptionText = "Allow location access so you can track your child’s device in real-time.",
                steps = listOf(
                    "1. Open Settings",
                    "2. Go to Location",
                    "3. Enable location for ChildApp"
                )
            )
        )

        permissionScreens.add(
            PermissionFragment(
                iconRes = R.drawable.ic_permission_battery,
                titleText = "Disable Battery Optimization",
                descriptionText = "Prevent the system from stopping background services.",
                steps = listOf(
                    "1. Open Settings",
                    "2. Go to Battery",
                    "3. Disable Battery Optimization for ChildApp"
                )
            )
        )
    }

    private fun updateButtons() {
        btnBack.isEnabled = currentPage > 0
        btnNext.text = if (currentPage == permissionScreens.size - 1) "Finish" else "Next"
        progressIndicator.progress = ((currentPage + 1) * 100 / permissionScreens.size)
    }

    private fun updateNextButtonState() {
        // Enable Next only if the current page is granted
        btnNext.isEnabled = grantedMap[currentPage] == true
    }

    // --- Callback from PermissionFragment ---
    override fun onCheckPermission() {
        // Mark the current page as granted
        grantedMap[currentPage] = true
        updateNextButtonState()
    }

    private fun finishOnboarding() {
        // TODO: Save onboarding flag in SharedPreferences / launch MainActivity
        finish()
    }
}