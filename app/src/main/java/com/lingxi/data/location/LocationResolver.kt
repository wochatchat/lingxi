package com.lingxi.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 位置快照（R15，最小实现）：只读最后已知位置，不主动定位（省电 + 无后台位置诉求）。
 * 用途：晨报城市未配置时按位置精化天气。需要 ACCESS_FINE_LOCATION（权限中心页管理）。
 */
@Singleton
class LocationResolver @Inject constructor(
    @ApplicationContext private val app: Context,
) {
    /** 最后已知位置 → (lat, lon)；无权限/无定位记录返回 null */
    fun lastKnownOrNull(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            return loc.latitude to loc.longitude
        }
        return null
    }
}
