package com.parentalcontrol.childapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.parentalcontrol.childapp.admin.ChildAdminReceiver

class DebugResetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply { text = "Deactivate & Uninstall" }
        setContentView(btn)

        btn.setOnClickListener {
            // 1️⃣ Deactivate Device Admin
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, ChildAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin)
            }

            // 2️⃣ Launch uninstall intent
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}
