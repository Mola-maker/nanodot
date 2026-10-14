package io.github.nanomuse.apiproof

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ProofSlot(val cap: Int, val casePrefix: String) {
    Chat(64, "foreground-chat"), CareInitial(1024, "foreground-care-initial"), CareNext(1024, "foreground-care-next")
}
data class FocusLineage(val originalSnapshot: String, val afterSnapshot: String,
    val originalVersion: Long, val afterVersion: Long, val sameHost: Boolean,
    val originalRef: String, val verifiedRef: String, val originalFocused: Boolean,
    val afterFocused: Boolean, val secondFocused: Boolean, val origin: String, val count: Int)

/** Immutable root reservation admission. Denial never grants a replacement slot. */
class ForegroundProofContract(admission: JSONObject) {
    private var next = 0
    private var active: ProofSlot? = null
    private var failed = false
    private var observable = false
    val run: String
    val owner: String
    init {
        admission.exact("schema","run","owner","serial","source","main_apk_sha256","test_apk_sha256","certificate_sha256","reservations")
        require(admission.get("schema") == 1 && admission.get("serial") == "emulator-5584")
        run = admission.getString("run"); owner = admission.getString("owner")
        require(UUID.fromString(run).toString() == run && UUID.fromString(owner).toString() == owner)
        require(admission.getString("source").matches(Regex("[0-9a-f]{40}")))
        listOf("main_apk_sha256","test_apk_sha256","certificate_sha256").forEach {
            require(admission.getString(it).matches(Regex("[0-9a-f]{64}")))
        }
        val reservations = admission.getJSONArray("reservations")
        require(reservations.length() == 3)
        val numbers = mutableSetOf<Long>()
        ProofSlot.entries.forEachIndexed { index, slot ->
            val entry = reservations.getJSONObject(index)
            entry.exact("slot","case","number","cap")
            require(entry.get("slot") == slot.name && entry.get("case") == "${slot.casePrefix}-$run")
            val number = entry.get("number")
            require(number is Number && number.toLong() > 0 && number.toDouble() == number.toLong().toDouble() && numbers.add(number.toLong()))
            require(entry.get("cap") == slot.cap)
        }
    }
    @Synchronized fun begin(slot: ProofSlot) {
        check(!failed && active == null && next < 3 && ProofSlot.entries[next] == slot)
        check(slot != ProofSlot.CareNext || observable)
        active = slot
    }
    @Synchronized fun complete(slot: ProofSlot) {
        check(!failed && active == slot)
        active = null; next++
    }
    @Synchronized fun fail() { failed = true }
    @Synchronized fun authorizeNext(evidence: FocusLineage) {
        check(!failed && active == null && next == 2 && !observable)
        require(evidence.originalSnapshot.isNotBlank() && evidence.afterSnapshot.isNotBlank() &&
            evidence.originalSnapshot != evidence.afterSnapshot && evidence.afterVersion > evidence.originalVersion &&
            evidence.sameHost && evidence.originalRef.isNotBlank() && evidence.originalRef == evidence.verifiedRef &&
            !evidence.originalFocused && evidence.afterFocused && !evidence.secondFocused &&
            evidence.origin == "Observable" && evidence.count == 1)
        observable = true
    }
}

object ForegroundWireGuard {
    fun validate(body: String, cap: Int, system: String, user: String, tools: JSONArray,
        temperature: Double?) {
        require(body.toByteArray(Charsets.UTF_8).size <= 524288 && system.isNotBlank() && user.isNotBlank())
        val parsed = JSONObject(body)
        val keys = mutableSetOf("model","stream","max_tokens","max_completion_tokens","stream_options","thinking","messages")
        if (temperature != null) keys.add("temperature")
        if (tools.length() > 0) keys.addAll(listOf("tools","tool_choice"))
        require(parsed.keys().asSequence().toSet() == keys)
        require(parsed.get("model") == "deepseek-flash" && parsed.get("stream") == true &&
            parsed.get("max_tokens") == cap && parsed.get("max_completion_tokens") == cap)
        require(sameJson(parsed.get("stream_options"), JSONObject().put("include_usage",true)))
        require(sameJson(parsed.get("thinking"),JSONObject().put("type","disabled")))
        if (temperature != null) require(parsed.get("temperature") is Number && parsed.getDouble("temperature") == temperature)
        val expected = JSONArray().put(JSONObject().put("role","system").put("content",system))
            .put(JSONObject().put("role","user").put("content",user))
        require(sameJson(parsed.get("messages"), expected))
        if (tools.length() > 0) require(parsed.get("tool_choice") == "auto" && sameJson(parsed.get("tools"),tools))
    }
}

internal fun JSONObject.exact(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
internal fun sameJson(first: Any?, second: Any?): Boolean = when {
    first is JSONObject && second is JSONObject -> {
        val keys = first.keys().asSequence().toSet()
        keys == second.keys().asSequence().toSet() && keys.all { sameJson(first.get(it),second.get(it)) }
    }
    first is JSONArray && second is JSONArray -> first.length() == second.length() &&
        (0 until first.length()).all { sameJson(first.get(it),second.get(it)) }
    first is Number && second is Number -> first.toDouble() == second.toDouble()
    else -> first == second
}

/** A cleanup callback must not run until every fixture-scoped sync inventory is absent. */
internal inline fun <T> withAbsentForegroundSync(
    ownerCount: () -> Long,
    conversationCount: () -> Long,
    messageCount: () -> Long,
    deleteOwnedChat: () -> T,
): T {
    val owners = ownerCount()
    val conversations = conversationCount()
    val messages = messageCount()
    check(owners == 0L && conversations == 0L && messages == 0L) { "Unexpected fixture sync evidence preserved" }
    return deleteOwnedChat()
}
