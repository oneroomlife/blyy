package com.azurlane.blyy.util

/**
 * Live2D 皮肤库「多选」的纯逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * 界面与 ViewModel 都只持有 `Set<String>`，所有集合语义（切换、全选、删后收敛、
 * 非法 id 剔除）集中在这里，避免三处各写一套导致选中数与实际删除数不一致。
 */
object Live2dSelection {

    /** 反斜杠（Windows 风格路径分隔符）：单独常量避免源码里出现难读的转义字符 */
    private const val BACKSLASH = "\\"

    /**
     * 切换单个 id 的选中态。
     * 非法 id（含路径分隔符/`..` 等）一律不进入选中集，防止把路径穿越串带进删除流程。
     */
    fun toggle(selected: Set<String>, id: String): Set<String> {
        if (!isSelectable(id)) return selected
        return if (id in selected) selected - id else selected + id
    }

    /** 全选/全不选（[ids] 通常为当前筛选结果，保证"全选"只作用于可见列表） */
    fun selectAll(selected: Set<String>, ids: Collection<String>, select: Boolean): Set<String> {
        val valid = ids.asSequence().filter { isSelectable(it) }.toSet()
        return if (select) selected + valid else selected - valid
    }

    /**
     * 与现有模型集合求交：删除/重新扫描后收敛选中集。
     * 若模型库已空，多选模式也应自动退出（返回空集，由调用方切 selecting=false）。
     */
    fun retainExisting(selected: Set<String>, existingIds: Collection<String>): Set<String> {
        val alive = existingIds.toSet()
        return selected.filterTo(LinkedHashSet()) { it in alive }
    }

    /** 当前列表是否已全选（空列表视为未全选） */
    fun isAllSelected(selected: Set<String>, ids: Collection<String>): Boolean {
        val valid = ids.filter { isSelectable(it) }
        return valid.isNotEmpty() && valid.all { it in selected }
    }

    /** 可选中：非空、单层目录名（与 Live2dLibrary.isValidId 同规则，但此处不依赖 Android） */
    fun isSelectable(id: String): Boolean =
        id.isNotBlank() &&
            !id.contains('/') &&
            !id.contains(BACKSLASH) &&
            !id.contains("..") &&
            id != ".thumbs"
}
