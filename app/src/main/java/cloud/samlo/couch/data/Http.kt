package cloud.samlo.couch.data

import okhttp3.OkHttpClient

/** The one HTTP client: callers derive their own timeouts from it with `newBuilder()`, sharing its pool and threads. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        // Never between https and plain http: the upgrade is pointless and the downgrade leaks.
        .followSslRedirects(false)
        .build()
}
