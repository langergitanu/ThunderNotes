package com.thundernotes.snip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for [SnipAccounts] (spec §7.3.6 —
 * multi-account Gemini/GLM keys should survive app restart). Installed as
 * the [SnipAccounts.persistenceHook] on app startup so every add/remove
 * persists immediately. On startup, [load] hydrates the in-memory
 * [SnipAccounts] from disk.
 *
 * Wire format: a JSON array of `{"id","provider","apiKey","label"}` objects.
 * Pure data (no secrets beyond the API keys the user typed in — the app is
 * spec'd as free + no source-code-hiding burden, §2.7).
 *
 * Call [init] once from `Application.onCreate` (or the first activity's
 * `onCreate`). Idempotent.
 */
object SnipAccountsStore {
    private const val PREFS_NAME = "thunder_snip_accounts"
    private const val KEY_ACCOUNTS = "accounts_json"
    private var prefs: android.content.SharedPreferences? = null

    /** Install the persistence hook + hydrate [SnipAccounts] from disk. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        // Hydrate the in-memory accounts from the persisted JSON.
        val loaded = runCatching {
            val json = p.getString(KEY_ACCOUNTS, null) ?: return@runCatching emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SnipAccount(
                    id = o.getString("id"),
                    provider = o.getString("provider"),
                    apiKey = o.getString("apiKey"),
                    label = o.optString("label", o.getString("provider")),
                )
            }
        }.getOrDefault(emptyList())
        SnipAccounts.loadFromStore(loaded)
        // Wire future add/remove → persist to disk.
        SnipAccounts.persistenceHook = SnipAccounts.PersistenceHook { accounts ->
            val arr = JSONArray()
            accounts.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("provider", a.provider)
                    put("apiKey", a.apiKey)
                    put("label", a.label)
                })
            }
            p.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
        }
    }
}
