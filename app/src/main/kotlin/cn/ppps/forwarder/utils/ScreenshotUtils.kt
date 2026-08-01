package cn.ppps.forwarder.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * 截图保存工具：保存 Bitmap 到系统相册，并自动打开图片查看
 */
object ScreenshotUtils {

    /**
     * 保存截图到相册并自动打开
     * @return 是否成功
     */
    fun saveAndOpen(context: Context, bitmap: Bitmap): Boolean {
        val uri = saveToGallery(context, bitmap) ?: return false
        openImage(context, uri)
        return true
    }

    /** 保存 Bitmap 到系统相册（Pictures/SmsForwarder），返回 content URI */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val resolver = context.contentResolver
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            val bytes = bos.toByteArray()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "SmsForwarder_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmsForwarder")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e("ScreenshotUtils", "保存截图失败: ${e.message}")
            null
        }
    }

    /** 用系统图片查看器打开 */
    fun openImage(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "image/jpeg")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ScreenshotUtils", "打开图片失败: ${e.message}")
        }
    }
}
