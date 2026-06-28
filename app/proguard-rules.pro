############################################
# GENERAL SAFE RULES
############################################

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

############################################
# ANDROID COMPONENTS (CRITICAL FOR YOUR APP)
############################################

# Activities
-keep class * extends android.app.Activity
-keep class * extends androidx.appcompat.app.AppCompatActivity

# Services (VPN + Accessibility)
-keep class * extends android.app.Service

# Broadcast Receivers
-keep class * extends android.content.BroadcastReceiver

# Device Admin Receiver
-keep class * extends android.app.admin.DeviceAdminReceiver

############################################
# ACCESSIBILITY SERVICE (VERY IMPORTANT)
############################################

-keep class com.parentalcontrol.childapp.accessibility.** { *; }

############################################
# VPN SERVICE (VERY IMPORTANT)
############################################

-keep class com.parentalcontrol.childapp.vpn.** { *; }

############################################
# DEVICE ADMIN RECEIVER
############################################

-keep class com.parentalcontrol.childapp.admin.** { *; }

############################################
# WORKMANAGER
############################################

-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }
-keep class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.Worker

############################################
# FIREBASE
############################################

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# If you use data models for Firebase
-keepclassmembers class com.parentalcontrol.childapp.** {
    @com.google.firebase.database.PropertyName <fields>;
}

############################################
# GOOGLE PLAY SERVICES (MAPS + LOCATION)
############################################

-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

############################################
# TENSORFLOW LITE
############################################

-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

############################################
# ML KIT
############################################

-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

############################################
# ZXING (QR CODE)
############################################

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

############################################
# VIEW BINDING
############################################

-keepclassmembers class * {
    public static *** inflate(...);
}

############################################
# KEEP ENUMS (YOU USE ActionType)
############################################

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

############################################
# PREVENT CRASHES FROM REFLECTION
############################################

-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}