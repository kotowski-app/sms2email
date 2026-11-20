package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Globe2Cancel: ImageVector
    get() {
        if (_globe2Cancel != null) {
            return _globe2Cancel!!
        }
        _globe2Cancel =
            ImageVector
                .Builder(
                    name = "Globe2Cancel",
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
                        moveTo(17.4f, 22f)
                        lineTo(16f, 20.6f)
                        lineToRelative(2.1f, -2.1f)
                        lineTo(16f, 16.4f)
                        lineTo(17.4f, 15f)
                        lineToRelative(2.1f, 2.1f)
                        lineTo(21.6f, 15f)
                        lineTo(23f, 16.4f)
                        lineToRelative(-2.07f, 2.1f)
                        lineTo(23f, 20.6f)
                        lineTo(21.6f, 22f)
                        lineTo(19.5f, 19.93f)
                        lineTo(17.4f, 22f)
                        close()
                        moveTo(12f, 22f)
                        quadTo(9.93f, 22f, 8.1f, 21.21f)
                        quadTo(6.28f, 20.43f, 4.93f, 19.08f)
                        quadTo(3.58f, 17.73f, 2.79f, 15.9f)
                        reflectiveQuadTo(2f, 12f)
                        quadTo(2f, 9.92f, 2.79f, 8.1f)
                        quadTo(3.58f, 6.27f, 4.93f, 4.93f)
                        quadTo(6.28f, 3.57f, 8.1f, 2.79f)
                        quadTo(9.93f, 2f, 12f, 2f)
                        reflectiveQuadToRelative(3.9f, 0.79f)
                        reflectiveQuadToRelative(3.17f, 2.14f)
                        quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
                        quadTo(22f, 9.92f, 22f, 12f)
                        quadToRelative(0f, 0.25f, -0.01f, 0.5f)
                        reflectiveQuadTo(21.95f, 13f)
                        horizontalLineTo(19.93f)
                        quadToRelative(0.05f, -0.25f, 0.06f, -0.5f)
                        reflectiveQuadTo(20f, 12f)
                        quadToRelative(0f, -0.5f, -0.06f, -1f)
                        reflectiveQuadTo(19.75f, 10f)
                        horizontalLineToRelative(-3.4f)
                        quadToRelative(0.07f, 0.5f, 0.11f, 1f)
                        reflectiveQuadToRelative(0.04f, 1f)
                        quadToRelative(0f, 0.25f, 0f, 0.5f)
                        reflectiveQuadTo(16.48f, 13f)
                        horizontalLineToRelative(-2f)
                        quadTo(14.5f, 12.75f, 14.5f, 12.5f)
                        reflectiveQuadToRelative(0f, -0.5f)
                        quadToRelative(0f, -0.5f, -0.04f, -1f)
                        reflectiveQuadTo(14.35f, 10f)
                        horizontalLineTo(9.65f)
                        quadTo(9.58f, 10.5f, 9.54f, 11f)
                        reflectiveQuadTo(9.5f, 12f)
                        reflectiveQuadToRelative(0.04f, 1f)
                        quadToRelative(0.04f, 0.5f, 0.11f, 1f)
                        horizontalLineTo(14f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(10.1f)
                        quadToRelative(0.3f, 1.07f, 0.77f, 2.06f)
                        reflectiveQuadTo(12f, 19.95f)
                        quadToRelative(0.45f, 0f, 0.89f, -0.05f)
                        reflectiveQuadToRelative(0.89f, -0.1f)
                        lineToRelative(0.45f, 1.95f)
                        quadToRelative(-0.58f, 0.13f, -1.11f, 0.19f)
                        reflectiveQuadTo(12f, 22f)
                        close()
                        moveTo(4.25f, 14f)
                        horizontalLineToRelative(3.4f)
                        quadTo(7.58f, 13.5f, 7.54f, 13f)
                        reflectiveQuadTo(7.5f, 12f)
                        reflectiveQuadTo(7.54f, 11f)
                        reflectiveQuadTo(7.65f, 10f)
                        horizontalLineTo(4.25f)
                        quadTo(4.13f, 10.5f, 4.06f, 11f)
                        reflectiveQuadTo(4f, 12f)
                        reflectiveQuadToRelative(0.06f, 1f)
                        reflectiveQuadToRelative(0.19f, 1f)
                        close()
                        moveTo(5.1f, 8f)
                        horizontalLineTo(8.05f)
                        quadTo(8.28f, 7.07f, 8.61f, 6.19f)
                        reflectiveQuadTo(9.4f, 4.45f)
                        quadTo(8.03f, 4.9f, 6.93f, 5.81f)
                        quadTo(5.83f, 6.72f, 5.1f, 8f)
                        close()
                        moveTo(9.4f, 19.55f)
                        quadTo(8.95f, 18.7f, 8.61f, 17.81f)
                        reflectiveQuadTo(8.05f, 16f)
                        horizontalLineTo(5.1f)
                        quadToRelative(0.72f, 1.27f, 1.83f, 2.19f)
                        reflectiveQuadTo(9.4f, 19.55f)
                        close()
                        moveTo(10.1f, 8f)
                        horizontalLineToRelative(3.8f)
                        quadTo(13.6f, 6.93f, 13.13f, 5.94f)
                        reflectiveQuadTo(12f, 4.05f)
                        quadToRelative(-0.65f, 0.9f, -1.13f, 1.89f)
                        reflectiveQuadTo(10.1f, 8f)
                        close()
                        moveToRelative(5.85f, 0f)
                        horizontalLineTo(18.9f)
                        quadTo(18.18f, 6.72f, 17.08f, 5.81f)
                        quadTo(15.98f, 4.9f, 14.6f, 4.45f)
                        quadToRelative(0.45f, 0.85f, 0.79f, 1.74f)
                        reflectiveQuadTo(15.95f, 8f)
                        close()
                    }
                }.build()
        return _globe2Cancel!!
    }

@Suppress("ktlint:standard:backing-property-naming")
private var _globe2Cancel: ImageVector? = null
