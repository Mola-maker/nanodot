package io.github.nanomuse.dot.gateway

import org.junit.Assert.*
import org.junit.Test

class GatewayOriginPolicyTest {
    @Test fun implicitCloudTokenAndUnloadedConfigNeverLookFresh() {
        val type = runCatching { Class.forName("io.github.nanomuse.dot.gateway.GatewayOriginPolicy") }.getOrNull()
        assertNotNull("Gateway freshness policy is missing", type)
        val target = type!!.getField("INSTANCE").get(null)
        val method = type.getMethod("isFresh", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        for (loaded in listOf(false,true)) for (account in listOf(false,true)) for (provider in listOf(false,true)) {
            assertEquals(loaded && !account && !provider, method.invoke(target,loaded,account,provider))
        }
    }
}
