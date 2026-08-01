package cn.ppps.forwarder.utils


import android.text.TextUtils
import cn.ppps.forwarder.R
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.entity.LocationInfo
import com.xuexiang.xutil.resource.ResUtils.getString

/**
 * 远程控制工具类
 */
@Suppress("UselessCallOnNotNull")
class HttpServerUtils private constructor() {

    companion object {

        // 本地版本 -> 允许的请求版本范围
        private val VERSION_COMPAT_MAP: Map<Int, IntRange> = mapOf(
            55 to (54..55)
        )

        //是否启用一键克隆
        var enableApiClone: Boolean by SharedPreference(SP_ENABLE_API_CLONE, true)

        //是否启用远程查短信
        var enableApiSmsQuery: Boolean by SharedPreference(SP_ENABLE_API_SMS_QUERY, true)

        //是否启用远程查通话
        var enableApiCallQuery: Boolean by SharedPreference(SP_ENABLE_API_CALL_QUERY, true)

        //是否启用远程查话簿
        var enableApiContactQuery: Boolean by SharedPreference(SP_ENABLE_API_CONTACT_QUERY, true)

        //是否启用远程加话簿
        var enableApiContactAdd: Boolean by SharedPreference(SP_ENABLE_API_CONTACT_ADD, true)

        //是否启用远程查电量
        var enableApiBatteryQuery: Boolean by SharedPreference(SP_ENABLE_API_BATTERY_QUERY, true)

        //是否启用远程WOL
        var enableApiWol: Boolean by SharedPreference(SP_ENABLE_API_WOL, true)

        //是否启用远程找手机
        var enableApiLocation: Boolean by SharedPreference(SP_ENABLE_API_LOCATION, false)

        //是否启用远程摄像头（被控端摄像头推流到控制端）
        var enableApiCamera: Boolean by SharedPreference(SP_ENABLE_API_CAMERA, true)

        //远程找手机定位缓存
        var apiLocationCache: LocationInfo by SharedPreference(SP_API_LOCATION_CACHE, LocationInfo())

        //WOL历史记录
        var wolHistory: String by SharedPreference(SP_WOL_HISTORY, "")

        //判断版本是否一致
        @Throws(Exception::class)
        fun compareVersion(cloneInfo: CloneInfo) {
            val versionCode = cloneInfo.versionCode
            if (versionCode == 0) throw Exception(getString(R.string.version_code_required))

            val requestVersion = versionCode.toString().substring(1).toInt()
            val localVersion = AppUtils.getAppVersionCode().toString().substring(1).toInt()
            val compatibleRange = VERSION_COMPAT_MAP[localVersion]
            Log.d("HttpServerUtils", "compareVersion: localVersion=$localVersion, requestVersion=$requestVersion, compatibleRange=$compatibleRange")
            val isCompatible = if (compatibleRange != null) {
                requestVersion in compatibleRange
            } else {
                requestVersion == localVersion
            }
            if (!isCompatible) {
                throw Exception(getString(R.string.inconsistent_version))
            }
        }

        //导出设置
        fun exportSettings(): CloneInfo {
            val cloneInfo = CloneInfo()
            cloneInfo.versionCode = AppUtils.getAppVersionCode()
            cloneInfo.versionName = AppUtils.getAppVersionName()
            cloneInfo.settings = SharedPreference.exportPreference()
            return cloneInfo
        }

        //还原设置
        fun restoreSettings(cloneInfo: CloneInfo): Boolean {
            return try {
                //保留设备名称、SIM卡主键/备注
                val extraDeviceMark = SettingUtils.extraDeviceMark
                val subidSim1 = SettingUtils.subidSim1
                val extraSim1 = SettingUtils.extraSim1
                val subidSim2 = SettingUtils.subidSim2
                val extraSim2 = SettingUtils.extraSim2
                //应用配置
                SharedPreference.clearPreference()
                if (!TextUtils.isEmpty(cloneInfo.settings)) {
                    SharedPreference.importPreference(cloneInfo.settings)
                }
                //需要排除的配置
                SettingUtils.extraDeviceMark = extraDeviceMark
                SettingUtils.subidSim1 = subidSim1
                SettingUtils.extraSim1 = extraSim1
                SettingUtils.subidSim2 = subidSim2
                SettingUtils.extraSim2 = extraSim2
                true
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("restoreSettings", e.message.toString())
                false
            }
        }
    }
}
