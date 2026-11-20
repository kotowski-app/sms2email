package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val StackedEmail: ImageVector
    get() {
        if (_stackedEmail != null) {
            return _stackedEmail!!
        }
        _stackedEmail =
            ImageVector
                .Builder(
                    name = "StackedEmail",
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
                        moveTo(7f, 17f)
                        quadTo(6.18f, 17f, 5.59f, 16.41f)
                        reflectiveQuadTo(5f, 15f)
                        verticalLineTo(5f)
                        quadTo(5f, 4.17f, 5.59f, 3.59f)
                        reflectiveQuadTo(7f, 3f)
                        horizontalLineTo(21f)
                        quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                        reflectiveQuadTo(23f, 5f)
                        verticalLineTo(15f)
                        quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                        reflectiveQuadTo(21f, 17f)
                        horizontalLineTo(7f)
                        close()
                        moveToRelative(7f, -4.7f)
                        lineTo(7f, 7.43f)
                        verticalLineTo(15f)
                        horizontalLineTo(21f)
                        verticalLineTo(7.43f)
                        lineTo(14f, 12.3f)
                        close()
                        moveTo(14f, 9.85f)
                        lineTo(21f, 5f)
                        horizontalLineTo(7f)
                        lineToRelative(7f, 4.85f)
                        close()
                        moveTo(3f, 21f)
                        quadTo(2.18f, 21f, 1.59f, 20.41f)
                        reflectiveQuadTo(1f, 19f)
                        verticalLineTo(6.5f)
                        horizontalLineTo(3f)
                        verticalLineTo(19f)
                        horizontalLineTo(19.5f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(3f)
                        close()
                        moveTo(21f, 7.35f)
                        quadToRelative(0f, -0.7f, 0f, -1.17f)
                        reflectiveQuadTo(21f, 5f)
                        horizontalLineTo(7f)
                        quadTo(7f, 5.7f, 7f, 6.18f)
                        reflectiveQuadTo(7f, 7.35f)
                        verticalLineTo(5f)
                        horizontalLineTo(21f)
                        verticalLineTo(7.35f)
                        close()
                    }
                }.build()
        return _stackedEmail!!
    }

@Suppress("ktlint:standard:backing-property-naming")
private var _stackedEmail: ImageVector? = null
