package com.parentalcontrol.childapp.service

import android.util.Log
import com.google.firebase.database.*



data class AppRule(
    val allowed_from_hour: Int = 0,
    val packageName: String = "",
    val allowed_from_minute: Int = 0,
    val allowed_to_hour: Int = 23,
    val allowed_to_minute: Int = 59,
    val block_after_9pm: Boolean = false,
    val block_after_limit: Boolean = false,
    val blocked: Boolean = false,
    val daily_limit: Int = 0,
    val display_name: String = ""
)

object AppRuleManager {

    private var rulesListener: ValueEventListener? = null

    fun startRulesListener(
        childId: String,
        onRulesUpdated: (Map<String, AppRule>) -> Unit
    ) {

        val db = FirebaseDatabase.getInstance().reference

        // ✅ FIXED PATH (important)
        val rulesRef = db.child("app_rules").child(childId)

        // Remove previous listener if exists
        rulesListener?.let { rulesRef.removeEventListener(it) }

        rulesListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {

                val rulesMap = mutableMapOf<String, AppRule>()

                for (child in snapshot.children) {

                    val key = child.key ?: continue

                    // 🚨 SKIP NON-APP NODE (VERY IMPORTANT)
                    if (key == "block_schedule") continue

                    val rule = child.getValue(AppRule::class.java)

                    if (rule != null) {

                        rulesMap[key] = rule

                        Log.d(
                            "RULE_DEBUG",
                            "Loaded rule for: $key → $rule"
                        )
                    }
                }

                Log.d(
                    "RULE_FLOW",
                    "Total rules loaded: ${rulesMap.size}"
                )

                onRulesUpdated(rulesMap)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("RULE_ERROR", error.message)
            }
        }

        rulesRef.addValueEventListener(rulesListener!!)
    }

    fun stopRulesListener(childId: String) {

        val db = FirebaseDatabase.getInstance().reference

        val rulesRef = db.child("app_rules").child(childId)

        rulesListener?.let {
            rulesRef.removeEventListener(it)
        }

        rulesListener = null
    }
}