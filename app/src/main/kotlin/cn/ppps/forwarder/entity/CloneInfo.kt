package cn.ppps.forwarder.entity

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class CloneInfo(
    @SerializedName("version_code")
    var versionCode: Int = 0,

    @SerializedName("version_name")
    var versionName: String? = null,

    @SerializedName("settings")
    var settings: String = "",
) : Serializable
