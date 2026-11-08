package cloud.samlo.couch.phone

/** A phone changed the config: the home screen, when it is showing, loads it again. Set while home is resumed. */
object HomeReload {
    @Volatile var listener: (() -> Unit)? = null
}
