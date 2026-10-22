package com.fr.colorostools.ui.compose.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemShapes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fr.colorostools.ui.compose.components.material.withoutShapeAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁定「传给 material3 ListItem 的六个形状状态都不参与 M3 形状动画」这条不变量。
 *
 * 回归背景：material3 1.5.0-beta01 的 `ListItemDefaults.shapeForInteraction` 只在六个变体全是
 * `RoundedCornerShape` / `CornerBasedShape` 时才调用 internal 的 `rememberAnimatedShape`，而该动画用
 * `Interpolatable.lerp` 插值圆角且不做任何钳制。分段条目的内边圆角是 0dp，按压形状是 M3 的 16dp
 * expressive 形状，抬起时 16dp→0dp 的阻尼弹簧会过冲约 2.45%（-0.39dp，@3x 即 -1.1742859px），
 * `BackgroundNode` 取 outline 时抛 `Corner size in Px can't be negative`。
 * 条目短按后立即导航/销毁，长按则留在组合里，因此「功能页面长按任意 item」必崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialListShapesTest {

    @Test
    fun `all six states are not corner based shapes`() {
        val static = sampleShapes().withoutShapeAnimation()

        allShapes(static).forEach { shape ->
            assertFalse("形状不能是 CornerBasedShape：$shape", shape is CornerBasedShape)
        }
    }

    @Test
    fun `wrapper delegates outline and stays equal across recomposition`() {
        // 用一个固定返回同一 Outline 实例的哨兵形状，才能验证「原样委托、没有重算几何」
        val sentinel = Outline.Rectangle(Rect(0f, 0f, 10f, 10f))
        val inner = SentinelShape(sentinel)
        val static = sampleShapes().withoutShapeAnimation(inner)

        // 包同一个形状必须判等，否则每帧都生成「不相等」的新形状而反复重绘
        assertTrue(static.shape == sampleShapes().withoutShapeAnimation(inner).shape)
        assertEquals(inner.hashCode(), static.shape.hashCode())

        // 绘制完全委托给被包装的形状：同一个 Outline 实例
        assertSame(
            sentinel,
            static.shape.createOutline(Size(120f, 48f), LayoutDirection.Ltr, Density(1f)),
        )

        // 六个状态都指向同一个包装实例
        allShapes(static).forEach { assertSame(static.shape, it) }
    }

    private class SentinelShape(private val outline: Outline) : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = outline
    }

    private fun sampleShapes(): ListItemShapes = ListItemShapes(
        shape = RoundedCornerShape(0.dp),
        selectedShape = RoundedCornerShape(16.dp),
        pressedShape = RoundedCornerShape(16.dp),
        focusedShape = RoundedCornerShape(16.dp),
        hoveredShape = RoundedCornerShape(12.dp),
        draggedShape = RoundedCornerShape(16.dp),
    )

    private fun allShapes(shapes: ListItemShapes): List<Shape> = listOf(
        shapes.shape,
        shapes.selectedShape,
        shapes.pressedShape,
        shapes.focusedShape,
        shapes.hoveredShape,
        shapes.draggedShape,
    )
}
