package io.github.iamr0s.carvex.session

import java.util.Locale

/**
 * 一条可以推给车机的导航快照。
 *
 * 只包含**语义已确认**的字段：路名、剩余距离、到机动点距离与显示文本。
 * 下面三个数值（机动类型 / 引导状态 / 距离单位）在固件枚举没有标定前一律保持 null——
 * 宁可不显示箭头，也不显示错箭头；标定通过 `pushNavigation` 显式传入。
 */
data class NavigationSnapshot(
    val road: String? = null,
    val remainMeters: Int? = null,
    val toManeuverMeters: Int? = null,
    val maneuverType: Int? = null,
    val state: Int? = null,
    val distanceUnits: Int? = null,
) {
    fun remainText(): String? = distanceText(remainMeters)
    fun maneuverText(): String? = distanceText(toManeuverMeters)

    companion object {
        /** 距离显示文本：和手机导航一致的中文习惯，避开尚未标定的单位枚举。 */
        fun distanceText(meters: Int?): String? = when {
            meters == null || meters < 0 -> null
            meters < 1000 -> meters.toString() + "米"
            else -> String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)
        }
    }
}
