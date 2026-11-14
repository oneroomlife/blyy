package com.azurlane.blyy.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件解析 / 校验单测。
 *
 * 这些断言守护的是"恢复不可逆"这条产品约束：畸形或半坏的备份必须在**写入前**被拒绝，
 * 不能让用户出现"誓约恢复了一半、成绩丢了三成"的中间态。
 */
class BackupValidatorTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun payload(
        schema: Int = BackupPayload.CURRENT_SCHEMA,
        createdAt: Long = 1_700_000_000_000L,
        ships: List<ShipStateBackup> = listOf(ShipStateBackup("企业", "DOCK", true)),
        scores: List<ScoreBackup> = listOf(
            ScoreBackup(
                sessionId = "s1",
                mode = "IMAGE",
                difficulty = "HARD",
                totalQuestions = 10,
                correctAnswers = 8,
                totalScore = 70,
                hintsUsedTotal = 1,
                skippedQuestions = 0,
                totalPossibleScore = 100,
                timestamp = 1_700_000_000_000L,
                accuracy = 0.8f,
                averageScore = 7f
            )
        ),
        prefs: List<PrefBackup> = listOf(PrefBackup("commander_nickname", "string", "指挥官"))
    ) = BackupPayload(
        schema = schema,
        appVersion = "3.0.1",
        appVersionCode = 3_000_100,
        createdAt = createdAt,
        shipStates = ships,
        scores = scores,
        preferences = prefs
    )

    @Test
    fun `valid payload passes validation`() {
        BackupValidator.validate(payload())
    }

    @Test
    fun `json roundtrip keeps content`() {
        val original = payload()
        val text = json.encodeToString(BackupPayload.serializer(), original)
        val parsed = BackupValidator.parse(json, text)

        assertEquals(original.shipStates, parsed.shipStates)
        assertEquals(original.scores, parsed.scores)
        assertEquals(original.preferences, parsed.preferences)
        assertTrue(parsed.summary().contains("1 个誓约/收藏"))
    }

    @Test
    fun `unknown fields are tolerated`() {
        val text = """
            {
              "schema": 1,
              "appVersion": "3.0.1",
              "createdAt": 1700000000000,
              "shipStates": [],
              "scores": [],
              "preferences": [],
              "futureField": {"a": 1}
            }
        """.trimIndent()
        val parsed = BackupValidator.parse(json, text)
        assertEquals(0, parsed.shipStates.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported schema is rejected`() {
        BackupValidator.validate(payload(schema = BackupPayload.MAX_SUPPORTED_SCHEMA + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero createdAt is rejected`() {
        BackupValidator.validate(payload(createdAt = 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank ship name is rejected`() {
        BackupValidator.validate(payload(ships = listOf(ShipStateBackup("  ", "DOCK", true))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correct answers greater than total is rejected`() {
        val bad = payload().scores.first().copy(totalQuestions = 5, correctAnswers = 6)
        BackupValidator.validate(payload(scores = listOf(bad)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `accuracy out of range is rejected`() {
        val bad = payload().scores.first().copy(accuracy = 1.4f)
        BackupValidator.validate(payload(scores = listOf(bad)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative score is rejected`() {
        val bad = payload().scores.first().copy(totalScore = -1)
        BackupValidator.validate(payload(scores = listOf(bad)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too many ships is rejected`() {
        val many = List(BackupValidator.MAX_SHIPS + 1) { ShipStateBackup("舰$it", "DOCK", true) }
        BackupValidator.validate(payload(ships = many))
    }

    @Test
    fun `garbage text is rejected with friendly message`() {
        val error = runCatching { BackupValidator.parse(json, "not a json at all") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("不是有效的 BLYY 备份文件"))
    }

    @Test
    fun `pref backups keep their type and string set values`() {
        val prefs = listOf(
            PrefBackup("voice_language", "string", "JP"),
            PrefBackup("secretary_sd_scale", "float", "1.15"),
            PrefBackup("play_mode", "int", "2"),
            PrefBackup("auto_backup_enabled", "bool", "true"),
            PrefBackup("favorites", "string_set", setValues = listOf("https://a/1.mp3", "https://a/2.mp3"))
        )
        val text = json.encodeToString(BackupPayload.serializer(), payload(prefs = prefs))
        val parsed = BackupValidator.parse(json, text)

        assertEquals("float", parsed.preferences.first { it.name == "secretary_sd_scale" }.type)
        assertEquals("1.15", parsed.preferences.first { it.name == "secretary_sd_scale" }.value)
        assertEquals(
            listOf("https://a/1.mp3", "https://a/2.mp3"),
            parsed.preferences.first { it.name == "favorites" }.setValues
        )
    }

    @Test
    fun `summary lists all three sections`() {
        val text = payload().summary()
        assertTrue(text.contains("誓约/收藏"))
        assertTrue(text.contains("条成绩"))
        assertTrue(text.contains("项设置"))
    }
}
