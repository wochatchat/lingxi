package com.lingxi.data.screen

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一张待上传分析的截图（base64 由 LLM 客户端拼 data URL） */
data class ScreenImage(val mime: String, val base64: String)

/** 截屏来源抽象（F10）：引擎依赖接口，测试注入 fake */
interface ScreenCaptor {
    /** 是否有读取截图所需的权限（决定引擎给用户的提示语） */
    fun hasPermission(): Boolean = true

    /** 读取最近一张截图；null = 不可用/没找到 */
    suspend fun latestScreenshot(): ScreenImage?
}

/**
 * F10 截屏问答（v1 方案）：读取相册里最近一张截图（用户系统截屏后说「看看屏幕上这个」）。
 * 敏感内容防护：上传前引擎强制弹 ConfirmGate，用户确认才上行（PRD §16.2 本地预处理的精神）。
 */
@Singleton
class MediaStoreCaptor @Inject constructor(
    @ApplicationContext private val app: Context,
) : ScreenCaptor {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        app, Manifest.permission.READ_MEDIA_IMAGES,
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        app, android.Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun latestScreenshot(): ScreenImage? = withContext(Dispatchers.IO) {
        queryLatestImageBase64()
    }

    private fun queryLatestImageBase64(): ScreenImage? {
        val resolver = app.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
        )
        // ① 优先 Screenshots 目录（API 29+ 支持 RELATIVE_PATH）
        val shotUri = runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%Screenshots%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(0),
                ) else null
            }
        }.getOrNull()
        // ② 回落：相册里最近一张图（低版本 / 非标准目录截图）
        val anyUri = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                c.getLong(0),
            ) else null
        }
        val uri = shotUri ?: anyUri ?: return null
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return null
        val mime = resolver.getType(uri) ?: "image/jpeg"
        if (bytes.size > MAX_IMAGE_BYTES) return ScreenImage(mime, java.util.Base64.getEncoder().encodeToString(downscale(bytes)))
        return ScreenImage(mime, java.util.Base64.getEncoder().encodeToString(bytes))
    }

    /** 超大图粗降采样（避免 base64 体积超接口限制） */
    private fun downscale(bytes: ByteArray): ByteArray {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return bytes.take(MAX_IMAGE_BYTES).toByteArray()
        val scale = (MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true,
        )
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024 // 3MB 以上走降采样
        private const val MAX_DIM = 1600
    }
}
