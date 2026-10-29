package cloud.samlo.couch.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import java.io.File
import java.util.Locale

/** Stable key for a name: lowercase letters and digits only, so "HDMI 4" is `hdmi4`. */
fun slug(text: String) = text.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }

/** The Inputs row: the TV's passthrough inputs (HDMI and others). Empty without live-TV support. */
object InputSource {
    fun tiles(
        context: Context,
        config: Config,
        iconsDir: File?,
        w: Int = context.dp(Tokens.INPUT_W),
        h: Int = context.dp(Tokens.INPUT_H),
    ): List<Tile> {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) return emptyList()
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val all = manager.tvInputList
        // CEC devices show up as children of their port and often carry the device's real name.
        val children = all.filter { it.parentId != null }.groupBy { it.parentId }
        return all
            .filter { it.isPassthroughInput && it.parentId == null && !it.isHidden(context) }
            .map { info -> info to info.loadLabel(context).toString() }
            .sortedWith(compareBy({ it.first.type != TvInputInfo.TYPE_HDMI }, { it.second }))
            .mapNotNull { (info, systemLabel) ->
                val key = slug(systemLabel).ifEmpty { slug(info.id.substringAfterLast('/')) }
                if (key in config.hiddenInputs) return@mapNotNull null
                val device = children[info.id]?.firstOrNull()
                tile(context, manager, config, info, systemLabel, key, device, iconsDir, w, h)
            }
    }

    /** Every input, hidden ones too and without decoding custom images, for settings lists. */
    fun all(context: Context, config: Config): List<Tile> {
        val unfiltered = Config().apply {
            names = config.names
            inputIcons = config.inputIcons
        }
        return tiles(context, unfiltered, null)
    }

    private fun tile(
        context: Context,
        manager: TvInputManager,
        config: Config,
        info: TvInputInfo,
        systemLabel: String,
        key: String,
        device: TvInputInfo?,
        iconsDir: File?,
        w: Int,
        h: Int,
    ): Tile {
        val custom = info.loadCustomLabel(context)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && it != systemLabel }
        val deviceName = device?.let { (it.loadCustomLabel(context) ?: it.loadLabel(context))?.toString()?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val name = config.names[key] ?: custom ?: deviceName ?: systemLabel
        val disconnected = manager.getInputState(info.id) == TvInputManager.INPUT_STATE_DISCONNECTED
        val disconnectedLabel = context.getString(R.string.input_disconnected)
        val icon = InputIcon.resolve(config.inputIcons[key], name)
        return Tile(
            key = key,
            kind = Tile.Kind.INPUT,
            label = name,
            subtitle = when {
                disconnected -> disconnectedLabel
                name != systemLabel -> systemLabel
                else -> null
            },
            banner = Banners.memoized("input|$key|$w|${Banners.mtime(iconsDir, key)}") { Banners.custom(iconsDir, key, w, h, context) },
            iconRes = if (info.type != TvInputInfo.TYPE_HDMI && config.inputIcons[key] == null) R.drawable.ic_input else icon.res,
            cardColor = icon.color?.let { Color.parseColor(it) },
            iconWide = icon.wide,
            dimmed = disconnected,
            inputId = info.id,
            intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
