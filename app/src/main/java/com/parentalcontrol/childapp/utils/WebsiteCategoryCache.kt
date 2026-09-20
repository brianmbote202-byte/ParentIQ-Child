package com.parentalcontrol.childapp.utils

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

object WebsiteCategoryCache {

    private const val TAG = "CATEGORY_CACHE"

    private val cacheRef by lazy {

        FirebaseDatabase
            .getInstance()
            .getReference(
                "website_category_cache"
            )
    }


    // ============================================================
    // GET CATEGORY
    // ============================================================

    fun getCategory(
        domain: String,
        onResult: (String?) -> Unit
    ) {

        val key =
            normalizeKey(
                domain
            )

        if (
            key.isBlank()
        ) {

            onResult(null)

            return
        }


        cacheRef
            .child(key)
            .child("category")
            .get()
            .addOnSuccessListener { snapshot ->

                val category =
                    snapshot
                        .getValue(
                            String::class.java
                        )
                        ?.trim()
                        ?.lowercase()


                Log.d(
                    TAG,
                    "Cache result: $domain → $category"
                )


                onResult(
                    category
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Cache lookup failed",
                    error
                )

                onResult(null)
            }
    }


    // ============================================================
    // SAVE CATEGORY
    // ============================================================

    fun saveCategory(
        domain: String,
        category: String,
        source: String
    ) {

        val key =
            normalizeKey(
                domain
            )

        if (
            key.isBlank() ||
            category.isBlank()
        ) {

            return
        }


        val data =
            mapOf(
                "domain" to domain,
                "category" to category,
                "source" to source,
                "updatedAt" to ServerValue.TIMESTAMP
            )


        cacheRef
            .child(key)
            .updateChildren(
                data
            )
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "Cache saved: $domain → $category"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Cache save failed",
                    error
                )
            }
    }


    // ============================================================
    // UPDATE VISITED URL CATEGORY
    // ============================================================

    fun updateVisitedUrlCategory(
        childId: String,
        dateKey: String,
        entryKey: String,
        category: String
    ) {

        if (
            childId.isBlank() ||
            dateKey.isBlank() ||
            entryKey.isBlank() ||
            category.isBlank()
        ) {

            return
        }


        FirebaseDatabase
            .getInstance()
            .getReference(
                "analytics_browsing"
            )
            .child(childId)
            .child("visited_urls")
            .child(dateKey)
            .child(entryKey)
            .child("category")
            .setValue(
                category
            )
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "Updated visited URL category: " +
                            "$entryKey → $category"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Failed updating visited URL category",
                    error
                )
            }
    }


    // ============================================================
    // FIREBASE KEY
    // ============================================================

    private fun normalizeKey(
        domain: String
    ): String {

        return domain
            .trim()
            .lowercase()
            .removePrefix("www.")
            .replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
            .replace("/", "_")
    }
}