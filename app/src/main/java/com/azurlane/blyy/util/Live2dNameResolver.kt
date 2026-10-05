package com.azurlane.blyy.util

import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.util.PinyinHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live2D 模型目录名（拼音）→ 中文舰名反查器。
 *
 * 与 [Live2dViewModel] / 查看器共用：模型目录普遍沿用拼音命名（aierdeliqi_4），
 * 依据舰船数据库（Room）做拼音前缀反查还原中文舰名。
 * 进程级单例，持续收集舰船表刷新映射；查不到时回退原目录名，绝不猜测造假。
 */
@Singleton
class Live2dNameResolver @Inject constructor(
    shipDao: ShipDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 拼音 → 中文舰名（随舰船库刷新自动重建） */
    private val pinyinToName = MutableStateFlow(emptyMap<String, String>())

    init {
        scope.launch {
            shipDao.getAllShips().collect { ships ->
                pinyinToName.value = ships.associate { PinyinHelper.toPinyin(it.name) to it.name }
            }
        }
    }

    /**
     * 模型显示名：`aierdeliqi_4` → "埃尔德里奇 · 换装4"。
     * 先精确匹配全拼，再限定前缀长度做前缀匹配（覆盖"阿达尔伯特亲王"→adaerbote
     * 这类"目录只取舰名前半"的情况），都查不到时保留原目录名。
     */
    fun displayNameFor(id: String): String {
        val base = baseShipName(id) ?: return id
        val skinIndex = id.substringAfterLast('_').toIntOrNull()
        return if (skinIndex != null && skinIndex >= 2) "$base · 换装$skinIndex" else base
    }

    /** 基础中文舰名（无换装后缀），供语音查询等按舰名检索的场景；查不到返回 null */
    fun baseShipName(id: String): String? {
        val skinIndex = id.substringAfterLast('_').toIntOrNull()
        val base = if (skinIndex != null) id.substringBeforeLast('_') else id
        val pinyin = base.lowercase()
        return pinyinToName.value[pinyin]
            ?: pinyinToName.value.entries
                .filter { it.key.startsWith(pinyin) && pinyin.length >= 4 }
                .minByOrNull { it.key.length }
                ?.value
    }
}
