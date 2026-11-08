package cloud.samlo.couch.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.Banners
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputIcon
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.sync.Sync
import cloud.samlo.couch.ui.Tokens
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.util.Locale

/** What the TV has to show or start: its apps and inputs, their pictures, and starting one. Slow: call from a worker. */
object Catalog {
    /** The apps in the Index's order, then the hidden ones by name. */
    fun apps(context: Context, config: Config): JSONArray {
        val pm = context.packageManager
        val dir = UserFiles(context).icons
        val labels = AppSource.launchable(pm)
            .filter { it.activityInfo.packageName != context.packageName }
            .associate { it.activityInfo.packageName to (config.names[it.activityInfo.packageName] ?: it.loadLabel(pm).toString()) }
        val visible = labels.keys.filter { it !in config.hiddenApps }
        val usage = Usage.lastWeek(context)?.let { if (config.syncEnabled && config.syncAllTvs) Sync.withOtherTvs(context, it) else it }
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical = visible.sortedWith { a, b -> collator.compare(labels.getValue(a), labels.getValue(b)) }
        val ordered = AppOrder.sorted(alphabetical, config, usage?.mapValues { it.value.weekMs }) +
            labels.keys.filter { it in config.hiddenApps }.sortedWith { a, b -> collator.compare(labels.getValue(a), labels.getValue(b)) }
        return JSONArray(
            ordered.map { pkg ->
                JSONObject().put("package", pkg).put("label", labels.getValue(pkg)).put("pinned", pkg in config.pinnedApps)
                    .put("hidden", pkg in config.hiddenApps).put("banner", hasBanner(context, pkg, dir))
            },
        )
    }

    private fun hasBanner(context: Context, pkg: String, icons: java.io.File?) = icons?.let { java.io.File(it, "$pkg.png").isFile } == true ||
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadBanner(context.packageManager) != null }.getOrDefault(false)

    /** Every input, hidden ones too, with the icon id the phone can offer to change. */
    fun inputs(context: Context, config: Config): JSONArray = JSONArray(
        InputSource.all(context, config).map { tile ->
            JSONObject().put("key", tile.key).put("label", tile.label)
                .put("icon", InputIcon.resolve(config.inputIcons[tile.key], tile.label).id).put("hidden", tile.key in config.hiddenInputs)
        },
    )

    /** Starts [pkg] the way the Index does. False when it isn't launchable. */
    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        if (AppSource.launchable(pm).none { it.activityInfo.packageName == pkg }) return false
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) ?: return false
        return start(context, intent)
    }

    /** Switches to input [key] the way the Inputs panel does. False when there is no such input. */
    fun switchInput(context: Context, config: Config, key: String): Boolean {
        val tile = InputSource.all(context, config).firstOrNull { it.key == key } ?: return false
        // The tile's own intent is private to it; launching is its job, and it needs the main thread's toast looper only on failure.
        android.os.Handler(android.os.Looper.getMainLooper()).post { tile.launch(HomeAccessibilityService.context() ?: context) }
        return true
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        (HomeAccessibilityService.context() ?: context).startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** The picture for an app or an input, as base64 PNG; null when there is none. */
    fun icon(context: Context, config: Config, key: String): String? {
        val bitmap = appPicture(context, key) ?: inputPicture(context, config, key) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** The banner at 320x180, else the icon at 128x128. */
    private fun appPicture(context: Context, pkg: String): Bitmap? {
        val pm = context.packageManager
        if (AppSource.launchable(pm).none { it.activityInfo.packageName == pkg }) return null
        Banners.custom(UserFiles(context).icons, pkg, BANNER_W, BANNER_H, context, 0f)?.let { return it }
        val info = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg), 0).firstOrNull()
        val banner = info?.activityInfo?.loadBanner(pm) ?: info?.activityInfo?.applicationInfo?.loadBanner(pm)
        if (banner != null) return Banners.render(banner, BANNER_W, BANNER_H, 0f)
        return runCatching { Banners.render(pm.getApplicationIcon(pkg), ICON, ICON, 0f) }.getOrNull()
    }

    /** An input as Couch draws its card: custom image, or its icon on the card colour. */
    private fun inputPicture(context: Context, config: Config, key: String): Bitmap? {
        val tile = InputSource.all(context, config).firstOrNull { it.key == key } ?: return null
        Banners.custom(UserFiles(context).icons, key, BANNER_W, BANNER_H, context, 0f)?.let { return it }
        val res = tile.iconRes.takeIf { it != 0 } ?: return null
        val bitmap = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), Paint().apply { color = tile.cardColor ?: Tokens.surface })
        val drawable = context.getDrawable(res)?.mutate() ?: return bitmap
        drawable.setTint(Tokens.textPrimary.takeIf { tile.cardColor == null } ?: Color.WHITE)
        val h = ART
        val w = if (tile.iconWide) ART * 3 else ART
        drawable.setBounds((BANNER_W - w) / 2, (BANNER_H - h) / 2, (BANNER_W + w) / 2, (BANNER_H + h) / 2)
        drawable.draw(canvas)
        return bitmap
    }

    private const val BANNER_W = 320
    private const val BANNER_H = 180
    private const val ICON = 128
    private const val ART = 72
}
