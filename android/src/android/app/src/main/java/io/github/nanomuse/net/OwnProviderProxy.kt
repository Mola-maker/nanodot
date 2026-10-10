package io.github.nanomuse.net

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.model.ProviderConfig
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.ProviderCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings → Network → *HTTP proxy for own providers*: one HTTP proxy, per device, that only
 * the requests to own-key providers and the ChatGPT plan go through. Never nanoMuse Cloud, the
 * hub, a computer on the LAN or anything else the app fetches.
 *
 * It is installed once, in `MinisApp.onCreate`, as the process's default [ProxySelector]:
 * every OkHttp client built after that asks it per request, and it answers with the proxy for
 * the provider hosts it [routes] and with whatever the system would have said (the Wi-Fi
 * network's own proxy, usually none) for every other host. The hosts are the catalogue's base
 * URLs, the plan's (`chatgpt.com`, `auth.openai.com`) and the custom base URLs of the provider
 * instances a person added, minus the relay's. A proxy with a user name and password answers
 * 407 once; [authenticator] replies with the credentials on the clients that carry it.
 *
 * Off by default. Nothing here is synced.
 */
object OwnProviderProxy {
    data class Config(
        val enabled: Boolean = false,
        val host: String = "",
        val port: Int = 0,
        val user: String = "",
        val password: String = "",
    ) {
        /** Whether there is a proxy to use: on, with a host and a port. */
        val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
        val hasCredentials: Boolean get() = user.isNotBlank()
        val address: String get() = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        fun proxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host.trim(), port))
    }

    private const val PREFS = "nanomuse_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"

    @Volatile private var current: Config = Config()

    @Volatile private var hosts: Set<String> = ProviderReach.PLAN_HOSTS

    @Volatile private var relayHost: String = ""

    @Volatile private var installed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config = prefs(context).let { p ->
        Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            host = p.getString(KEY_HOST, "").orEmpty(),
            port = p.getInt(KEY_PORT, 0),
            user = p.getString(KEY_USER, "").orEmpty(),
            password = p.getString(KEY_PASSWORD, "").orEmpty(),
        )
    }.also { current = it }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, config.user)
            .putString(KEY_PASSWORD, config.password)
            .apply()
        current = config.copy(host = config.host.trim())
    }

    /** The configuration in force, as the selector sees it (tests set it with [setForTests]). */
    fun active(): Config = current

    /**
     * Installs the selector as the process default and starts following the provider list for
     * the hosts to route. Idempotent; call before the first OkHttp client is built.
     */
    fun install(context: Context, providers: StateFlow<ProviderConfig>?) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        config(app)
        relayHost = hostOf(NanoMuseCloud.baseUrl(app))
        val catalogue = runCatching { ProviderCatalogue.load(app) }.getOrDefault(emptyList())
            .flatMap { listOfNotNull(it.baseUrl, it.baseUrlGlobal) }
            .mapNotNull { hostOf(it).takeIf(String::isNotBlank) }
        rebuildHosts(catalogue, emptyList(), null)
        ProxySelector.setDefault(Selector(ProxySelector.getDefault()))
        if (providers != null) {
            scope.launch {
                providers.collect { cfg ->
                    val relayId = runCatching { NanoMuseCloud.instance(app)?.id }.getOrNull()
                    val custom = cfg.instances.filter { it.id != relayId }.mapNotNull { it.customBaseURL }
                        .mapNotNull { hostOf(it).takeIf(String::isNotBlank) }
                    rebuildHosts(catalogue, custom, hostOf(NanoMuseCloud.baseUrl(app)))
                }
            }
        }
    }

    private fun rebuildHosts(catalogue: List<String>, custom: List<String>, relay: String?) {
        if (relay != null) relayHost = relay
        hosts = (ProviderReach.PLAN_HOSTS + catalogue + custom)
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && it != relayHost && !LanOnly.isLocal(it) }
            .toSet()
    }

    /** The hosts the proxy is used for (tests). */
    fun routedHosts(): Set<String> = hosts

    /** Whether [url] is the relay's (nanoMuse Cloud, or the one the person named): its refusals are read as the relay's. */
    fun isRelay(url: String): Boolean = relayHost.isNotBlank() && hostOf(url) == relayHost

    /** Whether a request to [host] goes through the proxy, with the configuration in force. */
    fun routes(host: String?): Boolean {
        val cfg = current
        if (!cfg.usable || host.isNullOrBlank()) return false
        val h = host.lowercase(Locale.ROOT)
        if (h == relayHost || LanOnly.isLocal(h)) return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    /** Replies to the proxy's 407 with the configured credentials, once per request. */
    val authenticator: Authenticator = authenticatorFor { current }

    private fun authenticatorFor(config: () -> Config): Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val cfg = config()
            if (!cfg.hasCredentials) return null
            if (response.request.header("Proxy-Authorization") != null) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(cfg.user, cfg.password))
                .build()
        }
    }

    /** What the Test row found out. */
    data class Probe(val ok: Boolean, val status: Int, val millis: Long, val host: String, val failure: ProviderReach.Reach?)

    /**
     * Fetches [url] through [config] (not through whatever is installed) and says what came
     * back; a 2xx–4xx from the host counts as reached, since the point is the path, not the
     * page. Runs on IO.
     */
    suspend fun probe(config: Config, url: String): Probe = withContext(Dispatchers.IO) {
        val host = hostOf(url)
        val started = System.currentTimeMillis()
        val cfg = config.copy(enabled = true)
        try {
            val client = OkHttpClient.Builder()
                .proxy(cfg.proxy())
                .proxyAuthenticator(authenticatorFor { cfg })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            client.newCall(Request.Builder().url(url).header("User-Agent", "Nanodot").build()).execute().use { r ->
                val body = if (r.code == 403) r.peekBody(4096).string() else ""
                val reach = ProviderReach.fromHttp(r.code, body, host, oauth = false)
                Probe(reach == null && r.code < 500, r.code, System.currentTimeMillis() - started, host, reach)
            }
        } catch (e: IOException) {
            val text = e.message ?: e.javaClass.simpleName
            Probe(false, 0, System.currentTimeMillis() - started, host, ProviderReach.classify(text, host) ?: ProviderReach.Reach(ProviderReach.Kind.UNREACHABLE, host, text))
        }
    }

    /** Tests only: the configuration and host list the selector uses, without a context. */
    fun setForTests(config: Config, routed: Set<String>, relay: String = "") {
        current = config
        hosts = routed.map { it.lowercase(Locale.ROOT) }.toSet()
        relayHost = relay.lowercase(Locale.ROOT)
    }

    /** The selector itself, exposed for the tests; the installed one wraps the system's. */
    class Selector(private val system: ProxySelector?) : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            if (uri != null && routes(uri.host)) return mutableListOf(current.proxy())
            return system?.select(uri) ?: mutableListOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            if (uri != null && routes(uri.host)) return
            system?.connectFailed(uri, sa, ioe)
        }
    }

    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
}
