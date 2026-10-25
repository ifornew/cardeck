package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机旋钮（KnobInput）HID 报告解析。
 *
 * 全部断言基于 2026-10-23 BMW ID6 实车日志：描述符原文 + 用户实测
 * 「上一曲、下一曲、语音、滚轮上下、确认键」时的报文字节。
 */
class KnobKeysTest {
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** BMW ID6 /info hidDevices KnobInput 描述符原文（48B，实车日志）。 */
    private val bmwKnobDescriptor =
        hex(
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 0a 24 02 95 01 81 02 " +
                "95 06 81 01 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
        )

    @Test
    fun knobDescriptorDetectedByWheelUsage() {
        assertNotNull("含 Wheel 的设备应识别为旋钮", KnobKeys.parseDescriptor(bmwKnobDescriptor))
    }

    @Test
    fun wheelReportsDecodeSignedDelta() {
        val map = KnobKeys.parseDescriptor(bmwKnobDescriptor)!!
        // 实车日志实测字节：00 01/+1、00 ff/-1、00 02/+2、00 fe/-2（滚轮上下）
        assertEquals(1, map.parse(hex("00 01")).wheelDelta)
        assertEquals(-1, map.parse(hex("00 ff")).wheelDelta)
        assertEquals(2, map.parse(hex("00 02")).wheelDelta)
        assertEquals(-2, map.parse(hex("00 fe")).wheelDelta)
        // 纯滚轮报告不带按钮
        assertFalse(map.parse(hex("00 01")).confirm)
        assertFalse(map.parse(hex("00 01")).back)
    }

    @Test
    fun buttonBitsDecodeConfirmAndBack() {
        val map = KnobKeys.parseDescriptor(bmwKnobDescriptor)!!
        val confirm = map.parse(hex("01 00"))
        assertTrue("bit0 = 旋钮按下（确认）", confirm.confirm)
        assertFalse(confirm.back)
        assertEquals(0, confirm.wheelDelta)

        val back = map.parse(hex("02 00"))
        assertTrue("bit1 = AC Back（返回）", back.back)
        assertFalse(back.confirm)

        // 全释放：状态位清零
        val idle = map.parse(hex("00 00"))
        assertFalse(idle.confirm)
        assertFalse(idle.back)
        assertEquals(0, idle.wheelDelta)
    }

    @Test
    fun shortReportIsTolerated() {
        val map = KnobKeys.parseDescriptor(bmwKnobDescriptor)!!
        // 截断报告（只有按钮字节）：按钮可读、滚轮字段越界按 0 处理，不抛异常
        val event = map.parse(hex("01"))
        assertTrue(event.confirm)
        assertEquals(0, event.wheelDelta)
    }

    @Test
    fun nonKnobDevicesReturnNull() {
        // BMW ButtonInput（Consumer 位图，无 Wheel）不能识别为旋钮
        val buttonDescriptor =
            hex(
                "05 0c 09 01 a1 01 05 0c 09 b5 09 b6 09 b0 15 00 25 01 75 01 95 03 81 02 75 05 95 01 81 01 c0",
            )
        assertNull(KnobKeys.parseDescriptor(buttonDescriptor))
        // 触摸屏描述符（Digitizer 页）同样不是旋钮
        val touchDescriptor =
            hex(
                "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 75 07 95 01 81 01 " +
                    "05 01 09 30 15 00 26 a0 05 75 10 95 01 81 02 09 31 15 00 26 1c 02 75 10 95 01 81 02 c0 c0",
            )
        assertNull(KnobKeys.parseDescriptor(touchDescriptor))
    }
}
