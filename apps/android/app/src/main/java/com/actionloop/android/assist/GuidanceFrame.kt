package com.actionloop.android.assist

import kotlinx.serialization.Serializable

/**
 * 最小化引导帧（指导书 8.4）。
 * 每帧只保留定位与安全判定所需的最小信息：包名白名单状态、页面哈希、时间戳、
 * 旋转/密度/窗口 Insets、节点或 OCR 文本块与归一化边界、敏感标志。
 * 密码文本、完整屏幕位图和无关通知内容不得进入本结构，也不得进入日志。
 */
@Serializable
data class GuidanceFrame(
    val packageName: String,
    val pageHash: String,
    val timestampMs: Long,
    val rotation: Int = 0,
    val densityDpi: Int = 0,
    val insets: Insets = Insets(0, 0, 0, 0),
    val textBlocks: List<TextBlock> = emptyList(),
    /** 窗口 FLAG_SECURE：出现即视为敏感，清除高亮并暂停媒体。 */
    val isSecure: Boolean = false,
    /** 锁屏/关键guard：出现即停止。 */
    val isLocked: Boolean = false,
    val allowlistedPackage: Boolean = false
) {
    @Serializable
    data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    @Serializable
    data class TextBlock(
        val text: String,
        val bounds: NormalizedBounds,
        /** 该文本块是否来自可编辑/密码字段（用于敏感判定，不记录实际内容）。 */
        val isEditable: Boolean = false,
        val isPassword: Boolean = false
    )
}

/** 归一化到 [0,1] 的边界，与分辨率/旋转无关。 */
@Serializable
data class NormalizedBounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    /** 合法归一化边界：全部坐标在 [0,1]，宽高非负且不超过 1。 */
    fun isValid(): Boolean =
        x in 0.0..1.0 && y in 0.0..1.0 &&
            width in 0.0..1.0 && height in 0.0..1.0 &&
            x + width <= 1.0001 && y + height <= 1.0001

    fun toPixel(displayWidth: Int, displayHeight: Int): android.graphics.Rect = android.graphics.Rect(
        (x * displayWidth).toInt(),
        (y * displayHeight).toInt(),
        ((x + width) * displayWidth).toInt(),
        ((y + height) * displayHeight).toInt()
    )

    companion object {
        fun fromPixel(rect: android.graphics.Rect, displayWidth: Int, displayHeight: Int): NormalizedBounds? {
            if (displayWidth <= 0 || displayHeight <= 0) return null
            return NormalizedBounds(
                rect.left.toDouble() / displayWidth,
                rect.top.toDouble() / displayHeight,
                rect.width().toDouble() / displayWidth,
                rect.height().toDouble() / displayHeight
            )
        }
    }
}
