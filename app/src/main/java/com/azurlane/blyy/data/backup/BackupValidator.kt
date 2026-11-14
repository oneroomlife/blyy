package com.azurlane.blyy.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 备份文件的解析与校验（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 为什么把校验单独抽出来：
 * 1. **恢复是覆盖型操作**，"恢复了一半"比"恢复失败"更糟——所以校验必须先跑完、整包通过；
 * 2. 校验规则需要被测试反复覆盖（构造畸形文件是 QA 的常规手段），而 `BackupManager`
 *    依赖 Context/DAO，只能靠 instrumentation 才能测；
 * 3. UI 侧（预览对话框）也需要在写入前拿到"这份文件到底能不能用"的结论。
 */
object BackupValidator {

    /** 上限：防止被构造的巨型/畸形文件拖死（正常用户量级远小于此） */
    const val MAX_SHIPS = 20_000
    const val MAX_SCORES = 100_000
    const val MAX_PREFS = 500
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 解析 + 校验。
     * @throws IllegalArgumentException 附带可展示给用户的中文原因
     */
    fun parse(json: Json, text: String): BackupPayload {
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("不是有效的 BLYY 备份文件（JSON 解析失败）", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("不是有效的 BLYY 备份文件（JSON 解析失败）", e)
        }
        validate(payload)
        return payload
    }

    /**
     * 语义校验：schema 版本、条目规模、成绩自洽性、舰名非空。
     * @throws IllegalArgumentException
     */
    fun validate(payload: BackupPayload) {
        require(payload.schema in BackupPayload.MIN_SUPPORTED_SCHEMA..BackupPayload.MAX_SUPPORTED_SCHEMA) {
            "备份格式版本 ${payload.schema} 不受支持（当前支持 ${BackupPayload.MIN_SUPPORTED_SCHEMA}..${BackupPayload.MAX_SUPPORTED_SCHEMA}）"
        }
        require(payload.createdAt > 0) { "备份时间无效" }
        require(payload.shipStates.size <= MAX_SHIPS) { "誓约条目异常（${payload.shipStates.size}）" }
        require(payload.scores.size <= MAX_SCORES) { "成绩条目异常（${payload.scores.size}）" }
        require(payload.preferences.size <= MAX_PREFS) { "设置项异常（${payload.preferences.size}）" }

        payload.shipStates.forEach {
            require(it.name.isNotBlank()) { "存在空舰名的誓约记录" }
            require(it.archiveType.isNotBlank()) { "存在空档案类型的誓约记录" }
        }
        payload.scores.forEach {
            require(it.totalQuestions in 0..10_000) { "成绩题数异常（${it.totalQuestions}）" }
            require(it.correctAnswers in 0..it.totalQuestions) {
                "成绩答对数异常（${it.correctAnswers}/${it.totalQuestions}）"
            }
            require(it.totalScore >= 0) { "成绩分数为负" }
            require(it.accuracy in 0f..1f) { "成绩准确率越界（${it.accuracy}）" }
        }
        payload.preferences.forEach {
            require(it.name.isNotBlank()) { "存在空的设置项名" }
        }
    }
}
