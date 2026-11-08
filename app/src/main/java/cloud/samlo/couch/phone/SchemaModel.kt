package cloud.samlo.couch.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shape of the `settings` message: groups of rows the phone draws generically. Plain data,
 * so the JSON it makes is tested without a TV. A row's [kind] is `toggle`, `choice`, `text` or
 * `swatches`; option values are whatever JSON value `config.json` holds for the key.
 */
object SchemaModel {
    class Option(val value: Any, val label: String, val color: String? = null)

    /** Show the row only while config [key] equals [equals]. */
    class When(val key: String, val equals: Any)

    class Row(
        val key: String,
        val title: String,
        val help: String,
        val kind: String,
        val options: List<Option> = emptyList(),
        val visibleWhen: When? = null,
    )

    class Group(val id: String, val title: String, val rows: List<Row>)

    fun toJson(groups: List<Group>): JSONObject = JSONObject().put("t", "settings").put(
        "groups",
        JSONArray(
            groups.map { g ->
                JSONObject().put("id", g.id).put("title", g.title).put("rows", JSONArray(g.rows.map(::row)))
            },
        ),
    )

    private fun row(r: Row): JSONObject {
        val out = JSONObject().put("key", r.key).put("title", r.title).put("kind", r.kind)
        if (r.help.isNotEmpty()) out.put("help", r.help)
        if (r.options.isNotEmpty()) {
            out.put(
                "options",
                JSONArray(
                    r.options.map { o ->
                        JSONObject().put("value", o.value).put("label", o.label).apply { o.color?.let { put("color", it) } }
                    },
                ),
            )
        }
        r.visibleWhen?.let { out.put("visibleWhen", JSONObject().put("key", it.key).put("equals", it.equals)) }
        return out
    }
}
