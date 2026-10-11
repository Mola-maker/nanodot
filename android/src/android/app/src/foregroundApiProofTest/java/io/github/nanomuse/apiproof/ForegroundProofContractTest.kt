package io.github.nanomuse.apiproof

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForegroundProofContractTest {
    private val run = "01234567-89ab-cdef-0123-456789abcdef"
    private fun admission() = JSONObject("""{"schema":1,"run":"$run","owner":"$run","serial":"emulator-5584","source":"${"a".repeat(40)}","main_apk_sha256":"${"b".repeat(64)}","test_apk_sha256":"${"c".repeat(64)}","certificate_sha256":"${"d".repeat(64)}","reservations":[{"slot":"Chat","case":"foreground-chat-$run","number":5,"cap":64},{"slot":"CareInitial","case":"foreground-care-initial-$run","number":6,"cap":1024},{"slot":"CareNext","case":"foreground-care-next-$run","number":7,"cap":1024}]}""")
    private fun denied(action: () -> Unit) { try { action(); fail("unsafe request was admitted") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {} }
    private fun lineage() = FocusLineage("old", "fresh", 3, 4, true, "one", "one", false, true, false, "Observable", 1)
    @Test fun refusesUnreservedOrDuplicateCases() {
        denied { ForegroundProofContract(admission().put("serial", "emulator-5580")) }
        denied { ForegroundProofContract(admission().put("schema", 2)) }
        val value = admission(); value.getJSONArray("reservations").getJSONObject(1).put("number", 5)
        denied { ForegroundProofContract(value) }
    }
    @Test fun refusesOutOfOrderAndIncompleteSlots() {
        val gate = ForegroundProofContract(admission())
        denied { gate.begin(ProofSlot.CareInitial) }
        gate.begin(ProofSlot.Chat)
        denied { gate.begin(ProofSlot.Chat) }
        denied { gate.begin(ProofSlot.CareInitial) }
        denied { gate.complete(ProofSlot.CareInitial) }
    }
    @Test fun permitsExactlyThreeCompletedExchangesWithObservableLineage() {
        val gate = ForegroundProofContract(admission())
        gate.begin(ProofSlot.Chat); gate.complete(ProofSlot.Chat)
        gate.begin(ProofSlot.CareInitial); gate.complete(ProofSlot.CareInitial)
        denied { gate.begin(ProofSlot.CareNext) }
        gate.authorizeNext(lineage()); gate.begin(ProofSlot.CareNext); gate.complete(ProofSlot.CareNext)
        denied { gate.begin(ProofSlot.CareNext) }; denied { gate.begin(ProofSlot.Chat) }
    }
    @Test fun failureDoesNotRefundOrRearmAReservation() {
        val gate = ForegroundProofContract(admission()); gate.begin(ProofSlot.Chat); gate.fail()
        denied { gate.complete(ProofSlot.Chat) }; denied { gate.begin(ProofSlot.Chat) }; denied { gate.begin(ProofSlot.CareInitial) }
    }
    @Test fun rejectsElapsedConfirmationWrongHostOrUnchangedFocusEvidence() {
        listOf(lineage().copy(afterSnapshot="old"), lineage().copy(afterVersion=3),
            lineage().copy(sameHost=false), lineage().copy(origin="UserConfirmed"),
            lineage().copy(originalFocused=true), lineage().copy(afterFocused=false),
            lineage().copy(verifiedRef="other"), lineage().copy(count=2), lineage().copy(secondFocused=true)).forEach { evidence ->
            val gate = ForegroundProofContract(admission())
            gate.begin(ProofSlot.Chat); gate.complete(ProofSlot.Chat); gate.begin(ProofSlot.CareInitial); gate.complete(ProofSlot.CareInitial)
            denied { gate.authorizeNext(evidence) }
            denied { gate.begin(ProofSlot.CareNext) }
        }
    }
    private fun body() = JSONObject("""{"model":"deepseek-flash","stream":true,"max_tokens":64,"max_completion_tokens":64,"stream_options":{"include_usage":true},"thinking":{"type":"disabled"},"messages":[{"role":"system","content":"Public system"},{"role":"user","content":"Reply with the word READY."}]}""")
    private fun validate(value: JSONObject) = ForegroundWireGuard.validate(value.toString(),64,"Public system","Reply with the word READY.",JSONArray(),null)
    @Test fun acceptsKnownPublicProviderBytes() { validate(body()) }
    @Test fun rejectsPrivateHistoryAndMediaBeforeTransmission() {
        val history=body(); history.getJSONArray("messages").put(JSONObject().put("role","assistant").put("content","private")); denied { validate(history) }
        val user=body(); user.getJSONArray("messages").getJSONObject(1).put("content","foreign user"); denied { validate(user) }
        val image=body(); image.getJSONArray("messages").getJSONObject(1).put("content",JSONArray().put(JSONObject().put("type","image_url"))); denied { validate(image) }
    }
    @Test fun rejectsUnknownFieldsCapsThinkingAndToolResults() {
        denied { validate(body().put("metadata",JSONObject().put("secret","private"))) }
        denied { validate(body().put("max_tokens",65)) }
        denied { validate(body().put("thinking",JSONObject().put("type","enabled"))) }
        denied { validate(body().put("tool_choice","auto")) }
        denied { validate(body().put("temperature",0)) }
    }
    @Test fun permitsExactPublicToolsButRejectsAdditionalOrChangedDefinitions() {
        val tools=JSONArray("""[{"type":"function","function":{"name":"public-tool","description":"Static public definition","parameters":{"type":"object","properties":{}}}}]""")
        val value=body().put("tools",tools).put("tool_choice","auto")
        ForegroundWireGuard.validate(value.toString(),64,"Public system","Reply with the word READY.",tools,null)
        val altered=JSONObject(value.toString()); altered.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("description","private content")
        denied { ForegroundWireGuard.validate(altered.toString(),64,"Public system","Reply with the word READY.",tools,null) }
    }
    @Test fun requiresCareTemperatureAndExactSnapshotContext() {
        val value=body().put("max_tokens",1024).put("max_completion_tokens",1024).put("temperature",0)
        value.getJSONArray("messages").getJSONObject(1).put("content","Public snapshot")
        ForegroundWireGuard.validate(value.toString(),1024,"Public system","Public snapshot",JSONArray(),0.0)
        denied { ForegroundWireGuard.validate(value.put("temperature",0.1).toString(),1024,"Public system","Public snapshot",JSONArray(),0.0) }
    }
}
