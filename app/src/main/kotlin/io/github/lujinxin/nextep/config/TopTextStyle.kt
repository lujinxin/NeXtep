package io.github.lujinxin.nextep.config

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import java.io.File

object TopTextStyle {
    const val DEFAULT_FAMILY = "default"
    const val DEFAULT_SIZE_SP = 16
    const val MIN_SIZE_SP = 10
    const val MAX_SIZE_SP = 28
    private val genericFamilies = listOf(
        DEFAULT_FAMILY to "系统默认",
        "sans-serif" to "无衬线",
        "serif" to "衬线",
        "monospace" to "等宽",
        "sans-serif-condensed" to "窄体",
    )
    data class SystemFont(val id: String, val label: String, val file: File, val index: Int)

    // Enumerate public system fonts, plus readable OEM font files which some
    // ROMs omit from SystemFonts. No theme-store/private storage is traversed.
    val systemFonts: List<SystemFont> by lazy {
        val available = runCatching { SystemFonts.getAvailableFonts().toList() }.getOrDefault(emptyList())
        val files = (available.mapNotNull { it.file } +
            listOf("/system/fonts", "/product/fonts", "/system_ext/fonts").flatMap { path ->
                File(path).listFiles()?.filter { it.canRead() && it.extension.lowercase() in setOf("ttf", "otf", "ttc") }.orEmpty()
            }).distinctBy { it.absolutePath }
        files.filterNot { it.name.contains("emoji", true) || it.name.contains("symbols", true) }
            .filterNot { it.name.contains("Bold", true) || it.name.contains("Italic", true) || it.name.contains("Thin", true) }
            .map { file ->
                val index = if (file.name.contains("CJK") && file.extension == "ttc") 2
                    else available.firstOrNull { it.file == file }?.ttcIndex ?: 0
                SystemFont("system:${file.absolutePath}#$index", fontLabel(file.nameWithoutExtension), file, index)
            }.sortedWith(compareBy<SystemFont> { if (it.label.contains("中文") || it.label.contains("OPPO") || it.label.contains("ColorOS")) 0 else 1 }
                .thenBy { it.label })
    }
    val families: List<Pair<String, String>> get() = genericFamilies.take(1) +
        systemFonts.map { it.id to it.label } + genericFamilies.drop(1)
    private val typefaces = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    private fun fontLabel(name: String): String = when (name) {
        "NotoSansCJK-Regular" -> "中文黑体 · Noto Sans CJK"
        "NotoSerifCJK-Regular" -> "中文宋体 · Noto Serif CJK"
        "OSans-RC-Regular" -> "OPPO Sans · 中文"
        "OSans-Ext-Regular" -> "OPPO Sans · 扩展"
        "Oplus-Serif" -> "ColorOS 衬线体"
        "SysSans-Hans-Regular" -> "系统黑体 · 简体中文"
        "SysSans-Hant-Regular" -> "系统黑体 · 繁体中文"
        else -> name.removeSuffix("-Regular").removeSuffix("-VF")
    }

    fun familyLabel(value: String): String = families.firstOrNull { it.first == value }?.second ?: "系统默认"

    fun resolveFamily(value: String?): String =
        value?.takeIf { family -> families.any { it.first == family } } ?: DEFAULT_FAMILY

    fun typeface(family: String, bold: Boolean): Typeface {
        val resolved = resolveFamily(family)
        return typefaces.getOrPut("$resolved|$bold") {
            val base = systemFonts.firstOrNull { it.id == resolved }?.let { option ->
                runCatching {
                    val font = Font.Builder(option.file).setTtcIndex(option.index).build()
                    Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                        .setSystemFallback("sans-serif").setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT)).build()
                }.getOrNull()
            } ?: Typeface.create(if (resolved == DEFAULT_FAMILY || resolved.startsWith("system:")) null else resolved, Typeface.NORMAL)
            Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
