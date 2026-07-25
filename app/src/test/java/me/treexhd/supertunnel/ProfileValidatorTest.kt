package me.treexhd.supertunnel

import me.treexhd.supertunnel.domain.model.*
import me.treexhd.supertunnel.domain.validation.ProfileValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test fun directProfileNeedsSshEndpointAndUsername() {
        assertTrue(ProfileValidator.validate(TunnelProfile()).errors.size >= 2)
    }
    @Test fun proxyModeRequiresProxy() {
        val profile = TunnelProfile(mode = TunnelMode.SSH_PROXY, ssh = SshConfig("server", 22, "user"))
        assertTrue(ProfileValidator.validate(profile).errors.any { it.contains("Proxy is required") })
    }
}
