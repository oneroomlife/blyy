package com.azurlane.blyy.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话续播的有效性判定。
 *
 * 守护点：过期/未开始播放的会话不能被"恢复"，否则用户第二天打开 App
 * 会莫名跳进昨天听到一半的台词。
 */
class PlayerSessionTest {

    @Test
    fun `fresh session is restorable`() {
        val now = 1_700_000_000_000L
        val session = PlayerSession(url = "https://a/1.mp3", positionMs = 12_000, savedAt = now - 1_000)
        assertTrue(session.isRestorable(now))
    }

    @Test
    fun `empty url is not restorable`() {
        val now = 1_700_000_000_000L
        assertFalse(PlayerSession(url = "", savedAt = now).isRestorable(now))
    }

    @Test
    fun `expired session is not restorable`() {
        val now = 1_700_000_000_000L
        val session = PlayerSession(
            url = "https://a/1.mp3",
            savedAt = now - PlayerSession.MAX_AGE_MS - 1
        )
        assertFalse(session.isRestorable(now))
    }

    @Test
    fun `clock moved backwards is not restorable`() {
        val now = 1_700_000_000_000L
        val session = PlayerSession(url = "https://a/1.mp3", savedAt = now + 60_000)
        assertFalse(session.isRestorable(now))
    }
}
