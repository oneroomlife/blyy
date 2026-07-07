package com.azurlane.blyy.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Icons.Rounded.Github: ImageVector
    get() {
        if (_github != null) {
            return _github!!
        }
        _github = ImageVector.Builder(
            name = "Rounded.Github",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f
        ).path(
            fill = SolidColor(Color(0xFF000000)),
            fillAlpha = 1.0f,
            stroke = null,
            strokeAlpha = 1.0f,
            strokeLineWidth = 1.0f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 1.0f,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12.0f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.47f, 2.0f, 12.0f)
            curveToRelative(0.0f, 4.42f, 2.87f, 8.17f, 6.84f, 9.5f)
            curveToRelative(0.5f, 0.08f, 0.66f, -0.23f, 0.66f, -0.5f)
            curveToRelative(0.0f, -0.22f, -0.01f, -0.81f, -0.01f, -1.59f)
            curveToRelative(-2.78f, 0.6f, -3.37f, -1.34f, -3.37f, -1.34f)
            curveToRelative(-0.46f, -1.16f, -1.11f, -1.47f, -1.11f, -1.47f)
            curveToRelative(-0.91f, -0.62f, 0.07f, -0.6f, 0.07f, -0.6f)
            curveToRelative(1.0f, 0.07f, 1.53f, 1.03f, 1.53f, 1.03f)
            curveToRelative(0.89f, 1.52f, 2.34f, 1.08f, 2.91f, 0.83f)
            curveToRelative(0.09f, -0.65f, 0.35f, -1.09f, 0.63f, -1.34f)
            curveToRelative(-2.22f, -0.25f, -4.56f, -1.11f, -4.56f, -4.94f)
            curveToRelative(0.0f, -1.09f, 0.39f, -1.98f, 1.03f, -2.68f)
            curveToRelative(-0.1f, -0.25f, -0.45f, -1.27f, 0.1f, -2.64f)
            curveToRelative(0.0f, 0.0f, 0.84f, -0.27f, 2.75f, 1.02f)
            curveToRelative(0.79f, -0.22f, 1.65f, -0.33f, 2.5f, -0.33f)
            curveToRelative(0.85f, 0.0f, 1.71f, 0.11f, 2.5f, 0.33f)
            curveToRelative(1.91f, -1.29f, 2.75f, -1.02f, 2.75f, -1.02f)
            curveToRelative(0.55f, 1.37f, 0.2f, 2.39f, 0.1f, 2.64f)
            curveToRelative(0.64f, 0.7f, 1.03f, 1.59f, 1.03f, 2.68f)
            curveToRelative(0.0f, 3.84f, -2.34f, 4.68f, -4.57f, 4.93f)
            curveToRelative(0.36f, 0.31f, 0.68f, 0.92f, 0.68f, 1.85f)
            curveToRelative(0.0f, 1.34f, -0.01f, 2.42f, -0.01f, 2.75f)
            curveToRelative(0.0f, 0.27f, 0.16f, 0.59f, 0.67f, 0.5f)
            curveTo(19.14f, 20.16f, 22.0f, 16.42f, 22.0f, 12.0f)
            curveToRelative(0.0f, -5.53f, -4.47f, -10.0f, -10.0f, -10.0f)
            close()
        }.build()
        return _github!!
    }

private var _github: ImageVector? = null
