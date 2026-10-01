package com.jarves.mh.data

import android.content.Context
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One independently configured model the user can run sessions against.
 *
 * AGENTIC is multi-model by design: several of these can coexist, each with its
 * own provider, base URL, model id and API key. A chat pins itself to one of
 * these ids, so different sessions can ride different providers at the same
 * time without re-keying anything global.
 *
 * The [label] is user-chosen ("Work DeepSeek", "Personal Claude", …) so the
 * picker reads as a flat list of *models* rather than one provider being "the"
 * provider. Metadata lives here; the secret itself never does — it is stored
 * encrypted in [ApiKeyVault] under [vaultScope].
 */
data class ConfiguredModel(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val dshApi: String = defaultDshApiForProvider(kind),
    /** Vault scope isolating this model's secret from every other model's. */
    val vaultScope: String = "model.$id",
) {
    val displayBaseUrl: String get() = baseUrl.ifBlank { kind.defaultBaseUrl }
    val displayModel: String get() = model.ifBlank { kind.defaultModel }

    /** Renders as the ProviderProfile the runtime bridge already understands. */
    fun toProfile(): ProviderProfile = ProviderProfile(
        kind = kind,
        baseUrl = displayBaseUrl,
        model = displayModel,
        dshApi = dshApi.ifBlank { defaultDshApiForProvider(kind) },
    )
}

/**
 * A flat, order-preserving registry of every model the user has configured.
 *
 * This replaces the older "one global provider" model. The first model added is
 * the active one (matching "at first one model of one provider"); every later
 * addition is independent and can be picked per chat. Removing the active model
 * falls back to the next survivor so a working model is always selected.
 *
 * Secrets are delegated to [ApiKeyVault] (Android Keystore AES-256-GCM); this
 * class persists only non-secret metadata, mirroring how [ApiKeyVault] keeps
 * pool metadata separate from encrypted values.
 */
class ModelRegistry(context: Context) {
    private val preferences = context.getSharedPreferences("pocket_model_registry", Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context)

    @Synchronized
    fun list(): List<ConfiguredModel> {
        val raw = preferences.getString(KEY_MODELS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val kindName = obj.optString("kind").ifBlank { return@mapNotNull null }
                val kind = runCatching { ProviderKind.valueOf(kindName) }.getOrNull() ?: return@mapNotNull null
                ConfiguredModel(
                    id = obj.optString("id").ifBlank { return@mapNotNull null },
                    label = obj.optString("label").ifBlank { kind.title },
                    kind = kind,
                    baseUrl = obj.optString("baseUrl"),
                    model = obj.optString("model"),
                    dshApi = obj.optString("dshApi").ifBlank { defaultDshApiForProvider(kind) },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The model new chats use by default; the first entry while any exists. */
    @Synchronized
    fun activeId(): String? = preferences.getString(KEY_ACTIVE, null) ?: list().firstOrNull()?.id

    @Synchronized
    fun active(): ConfiguredModel? {
        val id = activeId() ?: return null
        return list().firstOrNull { it.id == id } ?: list().firstOrNull()
    }

    fun byId(id: String?): ConfiguredModel? = id?.takeIf { it.isNotBlank() }?.let { target ->
        list().firstOrNull { it.id == target }
    }

    /** True when a usable secret is stored for [model]. */
    fun hasSecret(model: ConfiguredModel): Boolean = secretFor(model) != null

    /** Decrypts this model's secret, or null if it was never saved. */
    fun secretFor(model: ConfiguredModel): String? = vault.get(model.vaultScope, VAULT_PROVIDER_ID)

    /** Total number of models with a connected key — backs Home's "Total Agents". */
    fun connectedCount(): Int = list().count { hasSecret(it) }

    /**
     * Adds an independently configured model. The first addition becomes the
     * active one; later ones never disturb it.
     */
    @Synchronized
    fun add(
        label: String,
        kind: ProviderKind,
        baseUrl: String,
        model: String,
        dshApi: String = defaultDshApiForProvider(kind),
        secret: String,
    ): ConfiguredModel {
        val entry = ConfiguredModel(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifBlank { kind.title },
            kind = kind,
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            dshApi = dshApi.ifBlank { defaultDshApiForProvider(kind) },
        )
        val updated = list() + entry
        save(updated)
        // The first model added is the one in use, matching single-model UX.
        if (preferences.getString(KEY_ACTIVE, null) == null) setActive(entry.id)
        if (secret.isNotBlank()) vault.put(entry.vaultScope, VAULT_PROVIDER_ID, secret)
        return entry
    }

    /** Re-labels or reconfigures an existing model in place. */
    @Synchronized
    fun update(
        id: String,
        label: String? = null,
        kind: ProviderKind? = null,
        baseUrl: String? = null,
        model: String? = null,
        dshApi: String? = null,
        secret: String? = null,
    ): ConfiguredModel? {
        val entries = list().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = entries[index]
        val replaced = current.copy(
            label = label?.trim()?.ifBlank { current.label } ?: current.label,
            kind = kind ?: current.kind,
            baseUrl = baseUrl?.trim() ?: current.baseUrl,
            model = model?.trim() ?: current.model,
            dshApi = dshApi?.ifBlank { defaultDshApiForProvider(kind ?: current.kind) } ?: current.dshApi,
        )
        entries[index] = replaced
        save(entries)
        if (!secret.isNullOrBlank()) vault.put(replaced.vaultScope, VAULT_PROVIDER_ID, secret)
        return replaced
    }

    @Synchronized
    fun remove(id: String) {
        val entries = list()
        val target = entries.firstOrNull { it.id == id } ?: return
        val remaining = entries.filterNot { it.id == id }
        // Each model owns one isolated vault pool; drop it wholesale so no
        // encrypted secret outlives its metadata entry.
        vault.remove("${target.vaultScope}.$VAULT_PROVIDER_ID")
        save(remaining)
        // Keep a working model selected: fall back to the first survivor.
        if (activeId() == id) {
            preferences.edit().remove(KEY_ACTIVE).apply()
            remaining.firstOrNull()?.let { setActive(it.id) }
        }
    }

    @Synchronized
    fun setActive(id: String): Boolean {
        if (list().none { it.id == id }) return false
        preferences.edit().putString(KEY_ACTIVE, id).apply()
        return true
    }

    @Synchronized
    private fun save(entries: List<ConfiguredModel>) {
        val array = JSONArray()
        entries.forEach { model ->
            array.put(JSONObject().apply {
                put("id", model.id)
                put("label", model.label)
                put("kind", model.kind.name)
                put("baseUrl", model.baseUrl)
                put("model", model.model)
                put("dshApi", model.dshApi)
            })
        }
        preferences.edit().putString(KEY_MODELS, array.toString()).apply()
    }

    private companion object {
        const val KEY_MODELS = "models"
        const val KEY_ACTIVE = "active_model"
        const val VAULT_PROVIDER_ID = "key"
    }
}
