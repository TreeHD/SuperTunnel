package me.treexhd.supertunnel

import me.treexhd.supertunnel.domain.model.SshConfig
import me.treexhd.supertunnel.domain.model.TunnelMode
import me.treexhd.supertunnel.domain.model.TunnelProfile
import me.treexhd.supertunnel.domain.share.ProfileShareCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileShareCodecTest {
    @Test fun roundTripUsesSpntPrefixAndDoesNotExposeFieldsAsQueryText() {
        val profile = TunnelProfile(name = "Office & home", mode = TunnelMode.SSH_DIRECT, ssh = SshConfig("127.0.0.1", 22, "alice"))
        val encoded = ProfileShareCodec.encode(profile)
        assertTrue(encoded.startsWith("spnt:v1:"))
        assertTrue(!encoded.contains("127.0.0.1"))
        assertTrue(!encoded.contains("&password="))
        val decoded = ProfileShareCodec.decode(encoded).getOrThrow()
        assertEquals(profile.name, decoded.name)
        assertEquals(profile.mode, decoded.mode)
        assertNotEquals(profile.id, decoded.id)
    }
}
