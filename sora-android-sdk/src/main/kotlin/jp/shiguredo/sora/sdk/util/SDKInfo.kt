package jp.shiguredo.sora.sdk.util

import android.os.Build
import jp.shiguredo.sora.sdk.BuildConfig
import org.webrtc.WebrtcBuildVersion

class SDKInfo {

    companion object {

        fun sdkInfo(): String {
            return "Sora Android SDK " + BuildConfig.VERSION_NAME +
                " (" + BuildConfig.REVISION + ")"
        }

        fun libwebrtcInfo(): String {
            return "Shiguredo-build " + WebrtcBuildVersion.webrtc_branch +
                " (" + BuildConfig.LIBWEBRTC_VERSION + " " +
                WebrtcBuildVersion.webrtc_revision.substring(0, 7) + ")"
        }

        fun deviceInfo(): String {
            return "Android-SDK: " + Build.VERSION.SDK_INT + ", " +
                "Release: " + Build.VERSION.RELEASE + ", " +
                "Id: " + Build.ID + ", " +
                "Device: " + Build.DEVICE + ", " +
                "Hardware: " + Build.HARDWARE + ", " +
                "Brand: " + Build.BRAND + ", " +
                "Manufacturer: " + Build.MANUFACTURER + ", " +
                "Model: " + Build.MODEL + ", " +
                "Product: " + Build.PRODUCT
        }
    }
}
