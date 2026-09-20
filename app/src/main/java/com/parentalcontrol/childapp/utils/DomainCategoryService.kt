package com.parentalcontrol.childapp.utils

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

object DomainCategoryService {

    private const val TAG = "DOMAIN_CATEGORY"

    private val domainCategoriesRef by lazy {

        FirebaseDatabase
            .getInstance()
            .getReference("domain_categories")
    }

    fun getOrRegister(
        domain: String,
        callback: (String?) -> Unit
    ) {

        val normalizedDomain =
            normalizeDomain(domain)

        if (normalizedDomain.isBlank()) {

            Log.d(
                TAG,
                "Ignored blank domain"
            )

            callback(null)

            return
        }

        val domainKey =
            normalizedDomain
                .replace(".", "_")

        val domainRef =
            domainCategoriesRef
                .child(domainKey)

        domainRef
            .get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.exists()) {

                    val category =
                        snapshot
                            .child("category")
                            .getValue(
                                String::class.java
                            )
                            ?.trim()
                            ?.lowercase()

                    Log.d(
                        TAG,
                        "KNOWN DOMAIN: $normalizedDomain → $category"
                    )

                    callback(category)

                    return@addOnSuccessListener
                }

                val pendingData =
                    mapOf(
                        "domain" to normalizedDomain,
                        "category" to "pending",
                        "updatedAt" to ServerValue.TIMESTAMP
                    )

                domainRef
                    .setValue(pendingData)
                    .addOnSuccessListener {

                        Log.d(
                            TAG,
                            "REGISTERED DOMAIN: $normalizedDomain → pending"
                        )

                        callback("pending")
                    }
                    .addOnFailureListener { error ->

                        Log.e(
                            TAG,
                            "Failed registering domain: $normalizedDomain",
                            error
                        )

                        callback(null)
                    }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Domain lookup failed: $normalizedDomain",
                    error
                )

                callback(null)
            }
    }

    private fun normalizeDomain(
        domain: String
    ): String {

        var clean =
            domain
                .trim()
                .lowercase()
                .trimEnd('.')

        while (
            clean.startsWith("www.")
        ) {

            clean =
                clean.substring(4)
        }

        return clean
            .trim()
    }
}