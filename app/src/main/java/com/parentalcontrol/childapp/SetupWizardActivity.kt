package com.parentalcontrol.childapp

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.admin.ChildAdminReceiver
import com.parentalcontrol.childapp.ui.helpers.InstructionOverlayHelper
import android.net.Uri
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.parentalcontrol.childapp.overlay.BlockOverlayView
import com.parentalcontrol.childapp.ui.InstructionGuideOverlay
import com.parentalcontrol.childapp.utils.UsageHelper
import com.parentalcontrol.childapp.admin.ParentIQDevicePolicyManager

//import com.parentalcontrol.childapp.ui.SmartPointerGuide


class SetupWizardActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var stepProgress: TextView
    private lateinit var checklistLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var overlayHelper: InstructionOverlayHelper
    private lateinit var adminComponent: ComponentName

    private val handler = Handler(Looper.getMainLooper())
    private var accessibilityAttempted = false
    //private var vpnAttempted = false
    //private var requestOverlayPermission = false
    private var isSystemFlowActive = false
    private lateinit var btnAccessibilityHelp: MaterialButton
    private lateinit var guideOverlay: InstructionGuideOverlay
    //private lateinit var smartPointerGuide: SmartPointerGuide

    private lateinit var tvStepDescription: TextView
    private lateinit var tvInfoText: TextView
    private lateinit var stepIcon: ImageView

    private lateinit var tvProgressSummary: TextView

    //------------header-------
    private lateinit var tvSetupPercent: TextView
    private lateinit var tvSetupStatus: TextView

    private var currentStep = 1
    private lateinit var btnEnableSocialMonitoring: MaterialButton



    private lateinit var btnNotificationHelp: MaterialButton


    companion object {
        private const val TOTAL_STEPS = 9
        private const val PREFS_NAME = "child_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_CHILD_ID = "child_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val childId = prefs.getString(KEY_CHILD_ID, null)

        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)
            && !childId.isNullOrEmpty()) {

            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_setup)

        statusText = findViewById(R.id.statusText)
        stepProgress = findViewById(R.id.stepProgress)
        checklistLayout = findViewById(R.id.checklistLayout)
        progressBar = findViewById(R.id.setupProgress)

        //----------assist accessibilty on phone brands----------
        btnAccessibilityHelp = findViewById(R.id.btnAccessibilityHelp)
        btnAccessibilityHelp.setOnClickListener {
            showAccessibilityHelpDialog()
        }


            //---------header--------
        tvSetupPercent = findViewById(R.id.tvSetupPercent)
        tvSetupStatus = findViewById(R.id.tvSetupStatus)

        //----------------instruction overlay------------
        guideOverlay = InstructionGuideOverlay(this)

        overlayHelper = InstructionOverlayHelper(this)
        adminComponent = ComponentName(this, ChildAdminReceiver::class.java)

        //-----------smart pointer------------
        //smartPointerGuide = SmartPointerGuide(this)

        tvStepDescription = findViewById(R.id.tvStepDescription)
        tvInfoText = findViewById(R.id.tvInfoText)
        //----------notification dialogue-----


        //---------------
        stepIcon = findViewById(R.id.stepIcon)

        tvProgressSummary = findViewById(R.id.tvProgressSummary)




        //-----------enable notification access------
        btnEnableSocialMonitoring = findViewById(R.id.btnEnableSocialMonitoring)
        btnNotificationHelp =
            findViewById(R.id.btnNotificationHelp)

        btnNotificationHelp.setOnClickListener {

            showNotificationWarningDialog()
        }


        btnEnableSocialMonitoring.setOnClickListener {

            when (currentStep) {

                1 -> {

                    if (!isDeviceAdminEnabled()) {

                        enableDeviceAdmin()

                    } else {

                        detectCurrentStep()
                    }
                }

                2 -> enableVpn()

                3 -> handleAccessibility()

                4 -> enableLocationIfNeeded()

                5 -> requestSmsPermission()

                6 -> requestCallPermission()

                7 -> {

                    if (isNotificationAccessEnabled()) {

                        detectCurrentStep()

                    } else {

                        showNotificationDialog()

                    }

                }
                8 -> requestUsageAccess()

                9 -> requestOverlayPermission()
            }
        }


        detectCurrentStep()

    }


    override fun onResume() {
        super.onResume()

        updateChecklist()


        if (!isSystemFlowActive) {
            handler.postDelayed({
                detectCurrentStep()
            }, 300)
        }

        // Only validate step state (no navigation)
        validateCurrentStepOnly()


        //-------check vpn if enabled-----
        /*if (!isVpnGranted()) {

            Handler(Looper.getMainLooper()).postDelayed({

                val intent = VpnService.prepare(this)

                if (intent != null) {

                    vpnLauncher.launch(intent)
                }

            }, 500)
        }*/

        // =====================================================
        // AUTO START STATE HANDLING (FIXED DUPLICATION)
        // =====================================================
        if (isAutoStartActuallyEnabled()) {

            getSharedPreferences("setup_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("auto_start_done", true)
                .apply()

            //smartPointerGuide.hide()
            guideOverlay.hide()

            handler.postDelayed({
                detectCurrentStep()
            }, 500)
        }

        //------------------notification enable---------
        if (
            currentStep == 7 &&
            isNotificationAccessEnabled()
        ){

            handler.postDelayed({

                detectCurrentStep()

            },500)

        }

        // =====================================================
        // ACCESSIBILITY GUIDE STATE
        // =====================================================
        /*if (!isAccessibilityEnabled()) {

            smartPointerGuide.show(
                getAccessibilityInstruction(),
                getGuidePosition()
            )

        } else {
            smartPointerGuide.hide()
        }*/

        // =====================================================
        // NOTIFICATION ACCESS UI UPDATE
        // =====================================================
        /*val btn = findViewById<Button>(R.id.btnEnableSocialMonitoring)
        val tv = findViewById<TextView>(R.id.tvNotificationStatus)

        if (isNotificationAccessEnabled()) {

            tv.text = "Notification Access: Enabled"
            tv.setTextColor(android.graphics.Color.GREEN)

            btn.visibility = View.GONE

            updateChecklist()

            // Hide any guide once completed
            //smartPointerGuide.hide()

        } else {

            tv.text = "Notification Access: Not Enabled"
            tv.setTextColor(android.graphics.Color.RED)
        }*/

        /*Handler(Looper.getMainLooper()).postDelayed({

            enforceCurrentStep()

        }, 800)*/
    }



    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlayHelper.hide()
        super.onDestroy()
    }


    //----------validate current step-------
    //private var step = 1

    //private fun currentStep(): Int = step

    /*private fun renderStep(newStep: Int) {
        step = newStep
        startStep(newStep, getStepMessage(newStep))
    }*/
    private fun validateCurrentStepOnly() {

        when (currentStep) {

            1 -> {
                // Step 1 is complete when Device Admin is enabled.
                if (isDeviceAdminEnabled()) {
                    startStep(2, getStepMessage(2))
                }
            }

            2 -> if (isVpnGranted())
                startStep(3, getStepMessage(3))

            3 -> if (isAccessibilityEnabled())
                startStep(4, getStepMessage(4))

            4 -> if (isLocationReady())
                startStep(5, getStepMessage(5))

            5 -> if (isSmsPermissionGranted())
                startStep(6, getStepMessage(6))

            6 -> if (isCallPermissionGranted())
                startStep(7, getStepMessage(7))

            7 -> if (isNotificationStepDone())
                startStep(8, getStepMessage(8))

            8 -> if (isUsageAccessGranted())
                startStep(9, getStepMessage(9))

            9 -> if (Settings.canDrawOverlays(this))
                completeOnboarding()
        }
    }

    //---------------detect device brand----------
    private fun getDeviceBrand(): String {
        return android.os.Build.MANUFACTURER.lowercase()
    }

    //-----------------ADAPTIVE aCCESSIBILITY MESSAGE------------
    private fun getAccessibilityInstruction(): String {

        val brand = getDeviceBrand()

        return when {
            brand.contains("samsung") -> {
                "Enable Accessibility:\n\n" +
                        "1. Open Settings\n" +
                        "2. Tap Accessibility\n" +
                        "3. Tap Installed apps / Downloaded apps\n" +
                        "4. Select your app\n" +
                        "5. Turn ON service\n" +
                        "If asked: Allow Restricted Settings"
            }

            brand.contains("xiaomi") || brand.contains("redmi") -> {
                "Enable Accessibility:\n\n" +
                        "Settings → Additional Settings → Accessibility → Downloaded apps → Enable this app"
            }

            brand.contains("tecno") || brand.contains("infinix") -> {
                "Enable Accessibility:\n\n" +
                        "Settings → Accessibility → Services → Enable this app"
            }

            brand.contains("oppo") || brand.contains("realme") -> {
                "Enable Accessibility:\n\n" +
                        "Settings → Additional Settings → Accessibility → Downloaded services → Enable app"
            }

            else -> {
                "Enable Accessibility:\n\n" +
                        "Settings → Accessibility → Installed apps/services → Enable this app"
            }
        }
    }
    //----------show accessibility help dialogue---------
    private fun showAccessibilityHelpDialog() {

        val message = getAccessibilityInstruction()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    //-----------step message--------
    private fun getStepMessage(step: Int): String {
        return when (step) {

            1 -> {
                if (isDeviceAdminEnabled()) {
                    "Device protection is enabled"
                } else {
                    "Enable Device Admin protection"
                }
            }
            2 -> "Enable VPN for tracking protection"
            3 -> "Enable Accessibility Service"
            4 -> "Allow Location Access"
            5 -> "Allow SMS Monitoring Permission"
            6 -> "Allow Call Monitoring Permission"
            7 -> "Enable Notification Access"
            8 -> "Allow Usage Access"
            9 -> "Grant Overlay Permission"
            else -> "Setup Complete"
        }
    }
    //---------------show step guide------------
    private fun showStepGuide(step: Int) {

        val mainButton = findViewById<View>(R.id.btnEnableSocialMonitoring)

        when (step) {

            1 -> {

                val message = if (isDeviceAdminEnabled()) {
                    "Device protection is active."
                } else {
                    "Tap Continue to enable device protection."
                }

                guideOverlay.show(
                    mainButton,
                    "Device Protection",
                    message
                )
            }
            2 -> guideOverlay.show(
                mainButton,
                "VPN Setup",
                "Tap continue to enable VPN tracking."
            )

            3 -> guideOverlay.show(
                mainButton,
                "Accessibility",
                "Tap continue, then enable Accessibility service in settings."
            )

            4 -> guideOverlay.show(
                mainButton,
                "Location Access",
                "Tap continue to allow GPS tracking."
            )

            5 -> guideOverlay.show(
                mainButton,
                "SMS Permission",
                "Tap continue to allow SMS monitoring."
            )

            6 -> guideOverlay.show(
                mainButton,
                "Call Permission",
                "Tap continue to allow call tracking."
            )

            7 -> guideOverlay.show(
                mainButton,
                "Notifications",
                "Tap continue to enable notification access."
            )

            // FIXED: Step 8 = Usage Access
            8 -> guideOverlay.show(
                mainButton,
                "Usage Access",
                "Tap continue to allow GuardianIQ to access app usage information."
            )

            // FIXED: Step 9 = Overlay Permission
            9 -> guideOverlay.show(
                mainButton,
                "Overlay Permission",
                "Tap continue to allow GuardianIQ to display over other apps."
            )
        }
    }

    // ==========================================================
    // STEP DETECTIONprivate fun enableDeviceAdmin()
    // ==========================================================

    private fun detectCurrentStep() {

        if (isSystemFlowActive) return

        when {

            // =====================================================
            // STEP 1 — DEVICE OWNER / DEVICE ADMIN
            // =====================================================

            // =====================================================
// STEP 1 — DEVICE ADMIN
// =====================================================

            !isDeviceAdminEnabled() -> {

                startStep(1, getStepMessage(1))

            }

            // =====================================================
            // STEP 2 — VPN
            // =====================================================

            !isVpnGranted() -> {

                startStep(2, getStepMessage(2))

            }

            // =====================================================
            // STEP 3 — ACCESSIBILITY
            // =====================================================

            !isAccessibilityEnabled() -> {

                startStep(3, getStepMessage(3))

            }

            // =====================================================
            // STEP 4 — LOCATION
            // =====================================================

            !isLocationReady() -> {

                startStep(4, getStepMessage(4))

            }

            // =====================================================
            // STEP 5 — SMS
            // =====================================================

            !isSmsPermissionGranted() -> {

                startStep(5, getStepMessage(5))

            }

            // =====================================================
            // STEP 6 — CALL
            // =====================================================

            !isCallPermissionGranted() -> {

                startStep(6, getStepMessage(6))

            }

            // =====================================================
            // STEP 7 — NOTIFICATION
            // =====================================================

            !isNotificationStepDone() -> {

                startStep(7, getStepMessage(7))

            }

            // =====================================================
            // STEP 8 — USAGE ACCESS
            // =====================================================

            !isUsageAccessGranted() -> {

                startStep(8, getStepMessage(8))

            }

            // =====================================================
            // STEP 9 — OVERLAY
            // =====================================================

            !Settings.canDrawOverlays(this) -> {

                startStep(9, getStepMessage(9))

            }

            // =====================================================
            // COMPLETE
            // =====================================================

            else -> {

                completeOnboarding()

            }
        }
    }

    //-----------------is auto start enabled-------------
    private fun isAutoStartActuallyEnabled(): Boolean {

        val done = getSharedPreferences("setup_prefs", MODE_PRIVATE)
            .getBoolean("auto_start_done", false)

        val attempted = getSharedPreferences("setup_prefs", MODE_PRIVATE)
            .getBoolean("auto_start_attempted", false)

        val manufacturer = android.os.Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("tecno") -> done
            manufacturer.contains("infinix") -> done
            manufacturer.contains("samsung") -> attempted || done
            else -> done
        }
    }
    //-----------check if user has  opened auto start-------
    private fun hasUserOpenedAutoStart(): Boolean {
        return getSharedPreferences("setup_prefs", MODE_PRIVATE)
            .getBoolean("auto_start_attempted", false)
    }


    //-----------check if  notification is enabled---------
    private fun isNotificationStepDone(): Boolean {

        val bypassed = getSharedPreferences(

            "setup_prefs",
            MODE_PRIVATE

        ).getBoolean(

            "notification_bypassed",

            false

        )


        return isNotificationAccessEnabled() ||
                bypassed

    }

    //-------------------show notification dialogue----------
    private fun showNotificationWarningDialog() {

        AlertDialog.Builder(this)

            .setTitle("Optional Feature")

            .setMessage(

                "Notification monitoring is optional.\n\n" +

                        "GuardianIQ will continue setup without it.\n\n" +

                        "You can enable it later from Security Settings."

            )

            .setPositiveButton("Continue Setup") { _, _ ->


                getSharedPreferences(
                    "setup_prefs",
                    MODE_PRIVATE
                )

                    .edit()

                    .putBoolean(
                        "notification_bypassed",
                        true
                    )

                    .apply()


                detectCurrentStep()

            }

            .setNegativeButton("Go Back", null)

            .show()

    }

    private fun startStep(step: Int, message: String) {

        // Progress bar
        progressBar.progress = step

        // Current step card
        stepProgress.text = "Step $step of $TOTAL_STEPS"
        statusText.text = message

        // Premium header
        val percent = (step * 100) / TOTAL_STEPS

        tvSetupPercent.text = "$percent%"
        tvSetupStatus.text = "Step $step of $TOTAL_STEPS"

        btnNotificationHelp.visibility = View.GONE
        btnAccessibilityHelp.visibility = View.GONE


        if(step == 3){
            btnAccessibilityHelp.visibility = View.VISIBLE
        }

        if(step == 7){
            btnNotificationHelp.visibility = View.VISIBLE
        }

        showStepGuide(step)

        btnNotificationHelp.visibility =
            if(step == 7) View.VISIBLE
            else View.GONE


        btnAccessibilityHelp.visibility =
            if(step == 3) View.VISIBLE
            else View.GONE

        // Dynamic icon
        stepIcon.setImageResource(
            when (step) {
                1 -> android.R.drawable.ic_lock_lock
                2 -> android.R.drawable.ic_secure
                3 -> android.R.drawable.ic_menu_manage
                4 -> android.R.drawable.ic_menu_mylocation
                5 -> android.R.drawable.ic_dialog_email
                6 -> android.R.drawable.ic_menu_call
                7 -> android.R.drawable.ic_dialog_info
                8 -> android.R.drawable.ic_menu_view
                9 -> android.R.drawable.ic_popup_sync
                else -> android.R.drawable.ic_dialog_info
            }
        )

        // Step description
        tvStepDescription.text = when (step) {

            1 -> "Required to provide basic device protection."
            2 -> "Required to filter websites and monitor browsing."
            3 -> "Required to monitor activity and block harmful content."
            4 -> "Required for real-time location tracking."
            5 -> "Required for SMS monitoring."
            6 -> "Required for call activity monitoring."
            7 -> "Required for social media monitoring."
            8 -> "Required to monitor app usage and screen time."
            9 -> "Required to display blocked content warnings."
            else -> ""
        }

        // Info box
        tvInfoText.text = when (step) {

            1 -> "Tap Continue and activate Device Admin."
            2 -> "Accept the VPN permission request."
            3 -> "Enable Accessibility Service for this app."
            4 -> "Allow location access and turn on GPS."
            5 -> "Grant SMS permissions when prompted."
            6 -> "Grant Call permissions when prompted."
            7 -> "Tap Continue Setup to enable Notification Access.\n\n" +
                    "If your phone doesn't support it, you can skip this step."
            8 -> "Enable Usage Access for ParentIQ."
            9 -> "Allow Display Over Other Apps."
            else -> ""
        }


        currentStep = step

        // Execute step
    }

    //----------------------------show notification-----------
    private fun showNotificationDialog() {

        AlertDialog.Builder(this)

            .setTitle("Social Media Monitoring")

            .setMessage(

                "GuardianIQ can monitor WhatsApp, Instagram, Snapchat and other apps using Notification Access.\n\n" +

                        "Some phones don't support this feature.\n\n" +

                        "Would you like to enable it?"

            )

            .setPositiveButton("Enable") { _, _ ->

                openNotificationSettings()

            }

            .setNeutralButton("My Phone Doesn't Support This") { _, _ ->

                showNotificationWarningDialog()

            }

            .setNegativeButton("Cancel", null)

            .show()

    }
    /*private fun handleNotificationAccess() {
        val btn = findViewById<Button>(R.id.btnEnableSocialMonitoring)
        val tv = findViewById<TextView>(R.id.tvNotificationStatus)

        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val isTecno = manufacturer.contains("tecno")

        fun refreshUI() {
            if (isNotificationAccessEnabled()) {
                tv.text = "Notification Access: Enabled"
                tv.setTextColor(android.graphics.Color.GREEN)
                btn.visibility = View.GONE
                updateChecklist()
                handler.postDelayed({ detectCurrentStep() }, 300)
            } else if (isTecno) {
                tv.text = "Notification Access: Not supported on this device"
                tv.setTextColor(android.graphics.Color.RED)
                btn.visibility = View.GONE
                updateChecklist()
                Toast.makeText(
                    this,
                    "Notification monitoring is not available on Tecno devices. The wizard will continue.",
                    Toast.LENGTH_LONG
                ).show()
                handler.postDelayed({ detectCurrentStep() }, 500)
            } else {
                tv.text = "Notification Access: Not Enabled"
                tv.setTextColor(android.graphics.Color.RED)
                btn.visibility = View.VISIBLE
            }
        }

        refreshUI()

        btn.setOnClickListener {
            Toast.makeText(
                this,
                "Please enable Notification Access for this app: ${packageName}",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open notification settings", Toast.LENGTH_SHORT).show()
            }

            handler.postDelayed({ refreshUI() }, 800)
        }
    }*/



    private fun completeOnboarding() {

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        prefs.edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()

        // Hide launcher icon after onboarding is completed
        //hideLauncherIcon()



        // ⭐ Save SIM identifier
        val simId = com.parentalcontrol.childapp.utils.SecurityUtils.getSimIdentifier(this)

        getSharedPreferences("device_security", MODE_PRIVATE)
            .edit()
            .putString("original_sim", simId)
            .apply()

        // ⭐ Save Android device ID
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        getSharedPreferences("device_security", MODE_PRIVATE)
            .edit()
            .putString("device_id", androidId)
            .apply()

        //---------------caution user to disable battery optimization -----------
        if (!isBatteryOptimizationDisabled()) {

            Toast.makeText(
                this,
                "For best protection, please disable battery optimization later.",
                Toast.LENGTH_LONG
            ).show()
        }

        Toast.makeText(this, "Setup complete ✓", Toast.LENGTH_SHORT).show()


        //-----------------show warning after set up is complete------
        if (!isAutoStartActuallyEnabled()) {

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Recommended Protection")
                .setMessage(
                    "Auto Start is not enabled.\n\n" +
                            "ParentIQ may not restart automatically after a device reboot on some phones.\n\n" +
                            "You can enable Auto Start later from Security Settings."
                )
                .setPositiveButton("Continue") { _, _ ->

                    startActivity(
                        Intent(this, PairChildActivity::class.java)
                    )

                    finish()
                }
                .show()

            return
        }
        //startActivity(Intent(this, MainActivity::class.java))
        startActivity(Intent(this, PairChildActivity::class.java))
        finish()

        // START SELF-HEALING SERVICE
        try {

            ContextCompat.startForegroundService(
                this,
                Intent(
                    this,
                    com.parentalcontrol.childapp.service.SelfHealingService::class.java
                )
            )

            Log.e("SETUP", "✅ SelfHealingService started")

        } catch (e: Exception) {

            Log.e("SETUP", "❌ Failed starting SelfHealingService", e)
        }

    }

    // ==========================================================
    // CHECKS
    // ==========================================================

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent)
    }

    //==========device owner=======
    private fun isDeviceOwner(): Boolean {

        return ParentIQDevicePolicyManager.isDeviceOwner(this)
    }

    /*private fun isVpnGranted(): Boolean {

        val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)

        return prefs.getBoolean("vpn_allowed", false)
    }*/
    private fun isVpnGranted(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected =
            "com.parentalcontrol.childapp/com.parentalcontrol.childapp.accessibility.BrowserAccessibilityService"

        val result = enabled.split(":").any { it.equals(expected, ignoreCase = true) }

        Log.d("ACCESS_CHECK", "enabled=$enabled | result=$result")
        return result
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isLocationReady(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return granted && isGpsEnabled()
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }

    // ==========================================================
    // ACTIONS (SAFE & NON-BLOCKING)
    // ==========================================================

    private val deviceAdminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->

            isSystemFlowActive = false

            handler.postDelayed({
                detectCurrentStep()
                updateChecklist()
            }, 800)
        }

    private val vpnLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            // IMPORTANT
            if (VpnService.prepare(this) == null) {

                Log.e("VPN_SETUP", "✅ VPN permission granted")

                Handler(Looper.getMainLooper()).postDelayed({

                    startVpnService()

                }, 1000)

            } else {

                Log.e("VPN_SETUP", "❌ VPN permission denied")
            }

            handler.postDelayed({
                detectCurrentStep()
            }, 500)
        }
    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLocationService()
            }
            handler.postDelayed({ detectCurrentStep() }, 500)
        }
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->

            val granted = result.values.all { it }

            if (granted) {
                Toast.makeText(this, "SMS permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS permission required for monitoring", Toast.LENGTH_LONG).show()
            }

            handler.postDelayed({ detectCurrentStep() }, 500)
        }

    //call launcher
    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->

            val granted = result.values.all { it }

            if (granted) {
                Toast.makeText(this, "Call permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Call permissions required", Toast.LENGTH_LONG).show()
            }

            handler.postDelayed({ detectCurrentStep() }, 500)
        }
    //-------save vpn state-----
    private fun saveVpnAllowed() {

        val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)

        prefs.edit()
            .putBoolean("vpn_allowed", true)
            .apply()

        Log.e("VPN", "💾 VPN permission saved")
    }

    //call request function
    private fun requestCallPermission() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG

        )

        callPermissionLauncher.launch(permissions)
    }
    private fun enableDeviceAdmin() {

        isSystemFlowActive = true

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Parent permission required to protect the device."
            )
        }

        deviceAdminLauncher.launch(intent)
    }

    private fun enableVpn() {

        val intent = VpnService.prepare(this)

        if (intent != null) {

            vpnLauncher.launch(intent)

        } else {

            Handler(Looper.getMainLooper()).postDelayed({

                startVpnService()

            }, 1000)
        }
    }

    private fun startVpnService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, com.parentalcontrol.childapp.vpn.DnsVpnService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "VPN service failed", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ detectCurrentStep() }, 500)
    }

    private fun enableLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        if (!isGpsEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            startLocationService()
        }
    }

    //if needed enable sms permission
    private fun requestSmsPermission() {

        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )

        smsPermissionLauncher.launch(permissions)
    }

    private fun handleAccessibility() {
        if (isAccessibilityEnabled()) {
            handler.postDelayed({ detectCurrentStep() }, 500)
            return
        }

        if (!accessibilityAttempted) {
            accessibilityAttempted = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

    }

    //sms permission check
    private fun isSmsPermissionGranted(): Boolean {
        val receive = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val read = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return receive && read
    }

    //call permissions granting
    private fun isCallPermissionGranted(): Boolean {
        val phone = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val callLog = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        return phone && callLog
    }

    private fun startLocationService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, com.parentalcontrol.childapp.service.ChildLocationService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Location service failed", Toast.LENGTH_SHORT).show()
        }
    }

    //-----------------auto restart childapp---------
    private fun openAutoStartSettings() {

        try {

            val intent = Intent()

            intent.component = ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity"
            )

            startActivity(intent)

        } catch (e: Exception) {

            try {

                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)

            } catch (_: Exception) {
            }
        }
    }
    //-----------------auto start tracking-----------
    private fun isAutoStartAttempted(): Boolean {

        return getSharedPreferences(
            "setup_prefs",
            MODE_PRIVATE
        ).getBoolean(
            "auto_start_done",
            false
        )
    }
    //-------------auto start step------
    private fun handleAutoStartStep() {

        val btn = findViewById<View>(R.id.btnEnableSocialMonitoring)

        guideOverlay.show(
            btn,
            "Enable Auto Start",
            "Tap to open Auto Start settings. Enable this app to run in background."
        )

        btn.setOnClickListener {

            getSharedPreferences(
                "setup_prefs",
                MODE_PRIVATE
            ).edit()
                .putBoolean(
                    "auto_start_attempted",
                    true
                )
                .apply()

            openAutoStartSettings()

            Toast.makeText(
                this,
                "Enable Auto Start for best protection",
                Toast.LENGTH_LONG
            ).show()

            handler.postDelayed({

                detectCurrentStep()

            }, 1000)
        }
    }
    //---------------battery optimization risk------------
    private fun isBatteryOptimizationDisabled(): Boolean {

        val powerManager =
            getSystemService(POWER_SERVICE) as android.os.PowerManager

        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }
    //---------------battery optimization request-------
    private fun requestBatteryOptimizationDisable() {

        try {

            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )

            intent.data = Uri.parse("package:$packageName")

            startActivity(intent)

        } catch (e: Exception) {

            e.printStackTrace()
        }

        handler.postDelayed({
            detectCurrentStep()
        }, 1500)
    }

    //overlay logic
    private fun showBlockedOverlay(domain: String, reason: String = "Blocked by parent") {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            return
        }

        BlockOverlayView.show(this, domain, reason)
    }
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
        }
    }



    // ==========================================================
    // CHECKLIST
    // ==========================================================

    private fun updateChecklist() {

        checklistLayout.removeAllViews()

        var completed = 0
        val total = 9

        fun add(title: String, enabled: Boolean) {
            addChecklist(title, enabled)
            if (enabled) completed++
        }

        add("Device Protection", isDeviceAdminEnabled())
        add("Grant VPN Permission", isVpnGranted())
        add("Enable Accessibility Settings", isAccessibilityEnabled())
        add("Allow GPS Location Permissions", isLocationReady())
        add("Allow SMS Monitoring Permission", isSmsPermissionGranted())
        add("Allow Call Monitoring Permission", isCallPermissionGranted())
        add(
            "Allow Notifications Access / Social Media Monitoring",
            isNotificationStepDone()
        )
        add("Allow Usage Access", isUsageAccessGranted())
        add("Allow Overlay Permission", Settings.canDrawOverlays(this))

        tvProgressSummary.text =
            "$completed of $total protections enabled"
    }

    private fun addChecklist(title: String, ok: Boolean) {

        val view = TextView(this)

        view.text = if (ok) {
            "✓ $title"
        } else {
            "○ $title"
        }

        view.textSize = 15f
        view.setPadding(0, 12, 0, 12)

        view.setTextColor(
            if (ok)
                android.graphics.Color.parseColor("#2E7D32")
            else
                android.graphics.Color.parseColor("#616161")
        )

        checklistLayout.addView(view)
    }

    //------------enforce current step----------


    private fun openNotificationSettings() {

        try {

            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )

        } catch (e: Exception) {

            startActivity(
                Intent(Settings.ACTION_SETTINGS)
            )
        }
    }

    //---------app usage helper--------
    private fun isUsageAccessGranted(): Boolean {
        return UsageHelper.isUsageStatsGranted(this)
    }

    //----------------request usage access----------
    private fun requestUsageAccess() {

        startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )

        handler.postDelayed({

            detectCurrentStep()

        }, 1000)
    }

    private fun hideLauncherIcon() {
        try {
            val component = ComponentName(
                this,
                LauncherActivity::class.java
            )

            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            Log.d("SetupWizard", "Launcher icon hidden")

        } catch (e: Exception) {
            Log.e("SetupWizard", "Failed to hide launcher icon", e)
        }
    }
}