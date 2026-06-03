package com.azurlane.blyy.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 碧蓝航线风格切角矩形 — 指挥中心 HUD 面板常用形态
 */
fun chamferedShape(size: androidx.compose.ui.unit.Dp = 12.dp): CornerBasedShape =
    CutCornerShape(size)

fun diagonalChamferedShape(size: androidx.compose.ui.unit.Dp = 10.dp): CornerBasedShape =
    CutCornerShape(topStart = size, bottomEnd = size)

object BlyyShapes {
    val PanelSmall: Shape = chamferedShape(8.dp)
    val PanelMedium: Shape = chamferedShape(12.dp)
    val PanelLarge: Shape = chamferedShape(16.dp)
    val NavBar: Shape = diagonalChamferedShape(14.dp)
    val Card: Shape = chamferedShape(10.dp)
    val Button: Shape = chamferedShape(8.dp)
}
