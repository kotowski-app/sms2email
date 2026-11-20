package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Slot1: ImageVector
    get() {
        if (_slot1 != null) {
            return _slot1!!
        }
        _slot1 =
            ImageVector
                .Builder(
                    name = "Slot1",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                ).apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    ) {
                        moveTo(12f, 19f)
                        verticalLineTo(8f)
                        horizontalLineTo(8f)
                        verticalLineTo(5f)
                        horizontalLineToRelative(7f)
                        verticalLineTo(19f)
                        horizontalLineTo(12f)
                        close()
                    }
                }.build()
        return _slot1!!
    }

@Suppress("ktlint:standard:backing-property-naming")
private var _slot1: ImageVector? = null
