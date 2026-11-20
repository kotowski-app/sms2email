package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SyncArrowDown: ImageVector
    get() {
        if (_syncArrowDown != null) {
            return _syncArrowDown!!
        }
        _syncArrowDown =
            ImageVector
                .Builder(
                    name = "SyncArrowDown",
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
                        moveTo(2f, 19.5f)
                        verticalLineToRelative(-2f)
                        horizontalLineTo(3.43f)
                        quadTo(2.25f, 16.45f, 1.63f, 15.01f)
                        reflectiveQuadTo(1f, 12f)
                        quadTo(1f, 9.32f, 2.68f, 7.26f)
                        reflectiveQuadTo(7f, 4.65f)
                        verticalLineTo(6.7f)
                        quadTo(5.2f, 7.2f, 4.1f, 8.66f)
                        reflectiveQuadTo(3f, 11.98f)
                        quadToRelative(0f, 1.25f, 0.53f, 2.34f)
                        reflectiveQuadTo(5f, 16.2f)
                        verticalLineTo(14.5f)
                        horizontalLineTo(7f)
                        verticalLineToRelative(5f)
                        horizontalLineTo(2f)
                        close()
                        moveToRelative(8f, -0.15f)
                        verticalLineTo(17.3f)
                        quadToRelative(1.8f, -0.5f, 2.9f, -1.96f)
                        reflectiveQuadTo(14f, 12.02f)
                        quadTo(14f, 10.77f, 13.48f, 9.69f)
                        reflectiveQuadTo(12f, 7.8f)
                        verticalLineTo(9.5f)
                        horizontalLineTo(10f)
                        verticalLineToRelative(-5f)
                        horizontalLineToRelative(5f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(13.58f)
                        quadToRelative(1.17f, 1.05f, 1.8f, 2.49f)
                        quadTo(16f, 10.43f, 16f, 12f)
                        quadToRelative(0f, 2.67f, -1.67f, 4.74f)
                        reflectiveQuadTo(10f, 19.35f)
                        close()
                        moveTo(19.5f, 20f)
                        lineTo(16f, 16.5f)
                        lineToRelative(1.43f, -1.4f)
                        lineToRelative(1.07f, 1.08f)
                        verticalLineTo(4f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(16.2f)
                        lineToRelative(1.1f, -1.1f)
                        lineTo(23f, 16.5f)
                        lineTo(19.5f, 20f)
                        close()
                    }
                }.build()
        return _syncArrowDown!!
    }

@Suppress("ktlint:standard:backing-property-naming")
private var _syncArrowDown: ImageVector? = null
