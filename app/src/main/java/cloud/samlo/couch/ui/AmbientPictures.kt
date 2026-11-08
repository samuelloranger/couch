package cloud.samlo.couch.ui

/** Continue after the displayed file even when the folder's contents or order changed. */
internal fun nextAmbientPicture(pictures: List<String>, current: String?): String? {
    if (pictures.isEmpty()) return null
    return pictures[(pictures.indexOf(current) + 1) % pictures.size]
}
