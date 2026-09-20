package com.parentalcontrol.childapp.model

import com.parentalcontrol.childapp.util.AppCategoryClassifier


data class AppInfo
    ( val packageName: String = "",
      val appName: String = "",
      var category: String = "other",
      val iconBase64: String = "",
      val hidden: Boolean = false )