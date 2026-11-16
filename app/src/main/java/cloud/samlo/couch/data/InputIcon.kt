package cloud.samlo.couch.data

import cloud.samlo.couch.R

/**
 * Icons the user can pick for an input. Brand marks draw white on the brand colour; [AUTO]
 * guesses from the input's name. [match] is what a name must contain to be guessed as this,
 * and [label] is a string resource.
 */
enum class InputIcon(
    val id: String,
    val label: Int,
    val res: Int,
    val color: String? = null,
    val match: Regex? = null,
    /** Wordmarks (PS5, Roku...) need a wide slot or they shrink to a sliver. */
    val wide: Boolean = false,
) {
    AUTO("auto", R.string.icon_auto, 0),
    PS5("ps5", R.string.icon_ps5, R.drawable.brand_playstation5, "#003791", Regex("ps ?5|playstation ?5"), wide = true),
    PS4("ps4", R.string.icon_ps4, R.drawable.brand_playstation4, "#003791", Regex("ps ?4|playstation ?4"), wide = true),
    PLAYSTATION("playstation", R.string.icon_playstation, R.drawable.brand_playstation, "#0070D1", Regex("playstation|\\bps[0-9]?\\b")),
    XBOX("xbox", R.string.icon_xbox, R.drawable.brand_xbox, "#107C10", Regex("xbox")),
    SWITCH2("switch2", R.string.icon_switch2, R.drawable.brand_switch2, "#E60012", Regex("switch ?2"), wide = true),
    SWITCH("switch", R.string.icon_switch, R.drawable.brand_switch, "#E60012", Regex("switch|nintendo")),
    STEAM_DECK("steamdeck", R.string.icon_steamdeck, R.drawable.brand_steamdeck, "#1A9FFF", Regex("steam ?deck")),
    STEAM("steam", R.string.icon_steam, R.drawable.brand_steam, "#1B2838", Regex("steam")),
    APPLE_TV("appletv", R.string.icon_appletv, R.drawable.brand_appletv, "#1C1C1E", Regex("apple ?tv")),
    ROKU("roku", R.string.icon_roku, R.drawable.brand_roku, "#662D91", Regex("roku"), wide = true),
    SHIELD("shield", R.string.icon_shield, R.drawable.brand_nvidia, "#76B900", Regex("shield|nvidia")),
    CHROMECAST("chromecast", R.string.icon_chromecast, R.drawable.brand_googletv, "#4285F4", Regex("chromecast|google ?tv")),
    RASPBERRY("raspberrypi", R.string.icon_raspberrypi, R.drawable.brand_raspberrypi, "#A22846", Regex("raspberry|\\bpi\\b")),
    RETRO("retro", R.string.icon_retro, R.drawable.brand_retroarch, "#2B2B33", Regex("retro")),
    KODI("kodi", R.string.icon_kodi, R.drawable.brand_kodi, "#17B2E7", Regex("kodi")),
    MAC("mac", R.string.icon_mac, R.drawable.brand_apple, "#1C1C1E", Regex("\\bmac")),
    SONOS("sonos", R.string.icon_sonos, R.drawable.brand_sonos, "#1C1C1E", Regex("sonos"), wide = true),
    BOSE("bose", R.string.icon_bose, R.drawable.brand_bose, "#1C1C1E", Regex("bose"), wide = true),
    GAME("game", R.string.icon_game, R.drawable.ic_game, match = Regex("console|jeu|game")),
    BOX("box", R.string.icon_box, R.drawable.ic_tv, match = Regex("bell|fibe|box|cable|tivo|fire ?tv|décodeur")),
    PC("pc", R.string.icon_pc, R.drawable.ic_pc, match = Regex("\\bpc\\b|ordi|computer")),
    SERVER("server", R.string.icon_server, R.drawable.ic_server, match = Regex("serveur|server|nas")),
    MEDIA("media", R.string.icon_media, R.drawable.ic_media, match = Regex("blu-?ray|dvd|lecteur|player")),
    SOUND("sound", R.string.icon_sound, R.drawable.ic_speaker, match = Regex("barre|soundbar|son\\b|ampli|receiver")),
    CAMERA("camera", R.string.icon_camera, R.drawable.ic_camera, match = Regex("caméra|camera|webcam")),
    HDMI("hdmi", R.string.icon_hdmi, R.drawable.ic_hdmi),
    ANTENNA("antenna", R.string.icon_antenna, R.drawable.ic_antenna, match = Regex("live ?tv|télé en direct|antenne|antenna|tuner")),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO

        /** The user's pick, else the first icon whose pattern the name contains. */
        fun resolve(chosen: String?, name: String): InputIcon {
            of(chosen).takeIf { it != AUTO }?.let { return it }
            val lower = name.lowercase()
            return entries.firstOrNull { it.match?.containsMatchIn(lower) == true } ?: HDMI
        }
    }
}
