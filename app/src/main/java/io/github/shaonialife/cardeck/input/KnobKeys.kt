package io.github.shaonialife.cardeck.input

/**
 * 车机旋钮（iDrive knob / 方控滚轮）HID 报告 → 确认/返回/滚动事件。
 *
 * 与 ConsumerKeys 同源的描述符驱动实现（不写死车机型号）：
 * 在描述符的 Application Collection 里定位「Generic Desktop 页 + Wheel(0x38)」的
 * 设备——带滚轮的才是旋钮；报告位布局从描述符的 Input 条目逐项累加 bit 偏移得到。
 *
 * 2026-10-23 BMW ID6 实测描述符（/info hidDevices 中 name=KnobInput，48B）：
 * ```
 * 05 01 09 08 a1 01             GenericDesktop/Multi-axis, Application Collection
 *   05 09 09 01 15 00 25 01
 *   75 01 95 01 81 02           Button 页 Usage1 → bit0 = 旋钮按下（确认键）
 *   05 0c 0a 24 02 95 01 81 02  Consumer 页 AC Back(0x224) → bit1 = 返回键
 *   95 06 81 01                 6bit 常量填充
 *   05 01 09 38 15 81 25 7f
 *   75 08 95 01 81 06           Wheel → byte1 有符号相对增量
 * c0
 * ```
 * 实测报告（2 字节）：`00 01/+1`、`00 ff/-1`、`00 02/+2`、`00 fe/-2` = 滚轮上下；
 * `01 00` = 确认按下、`02 00` = 返回按下（位是状态位，松开回报 0，需要调用方做边沿检测）。
 */
object KnobKeys {
    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_BUTTON = 0x09
    private const val PAGE_CONSUMER = 0x0C
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_AC_BACK = 0x224

    /** 报告中的一个字段：Usage 归属 + bit 布局。 */
    class Field(
        val page: Int,
        val usage: Int,
        val bitOffset: Int,
        val bitSize: Int,
        val signed: Boolean,
    )

    data class KnobEvent(
        val confirm: Boolean,
        val back: Boolean,
        val wheelDelta: Int,
    )

    class KnobMap(
        private val fields: List<Field>,
    ) {
        fun parse(report: ByteArray): KnobEvent {
            var confirm = false
            var back = false
            var wheel = 0
            fields.forEach { f ->
                val raw = extractBits(report, f.bitOffset, f.bitSize, f.signed) ?: return@forEach
                when {
                    f.page == PAGE_BUTTON && f.usage == 1 -> confirm = raw != 0
                    f.page == PAGE_CONSUMER && f.usage == USAGE_AC_BACK -> back = raw != 0
                    f.page == PAGE_GENERIC_DESKTOP && f.usage == USAGE_WHEEL -> wheel = raw
                }
            }
            return KnobEvent(confirm, back, wheel)
        }

        /** 小端位序抽取 size 个 bit；signed 时按补码还原。 */
        private fun extractBits(
            report: ByteArray,
            offset: Int,
            size: Int,
            signed: Boolean,
        ): Int? {
            if (offset + size > report.size * 8) return null
            var value = 0
            for (i in 0 until size) {
                val bit = (report[(offset + i) / 8].toInt() shr ((offset + i) % 8)) and 0x01
                value = value or (bit shl i)
            }
            if (signed && size > 1 && value and (1 shl (size - 1)) != 0) {
                value -= (1 shl size)
            }
            return value
        }
    }

    /**
     * 解析 HID 描述符，含 Wheel Usage 的返回 KnobMap，否则 null（不是旋钮设备）。
     *
     * 只处理 Variable/Constant 输入项（旋钮描述符不用 Array）；Usage 按声明顺序
     * 依次对应同 Input 条目里的各字段（ReportSize 等宽切分）。
     */
    fun parseDescriptor(descriptor: ByteArray): KnobMap? {
        var index = 0
        var page = 0
        var logicalMin = 0
        var reportSize = 0
        var reportCount = 0
        val pendingUsages = ArrayList<Pair<Int, Int>>() // (page, usage)
        val fields = ArrayList<Field>()
        var bitOffset = 0

        fun flushInput(flags: Int) {
            val isConstant = (flags and 0x01) != 0
            val signed = logicalMin < 0
            val count = if (reportCount > 0) reportCount else 0
            for (i in 0 until count) {
                if (!isConstant) {
                    val u = pendingUsages.getOrNull(i)
                    if (u != null) {
                        fields += Field(u.first, u.second, bitOffset, reportSize, signed)
                    }
                }
                bitOffset += reportSize
            }
            pendingUsages.clear()
        }

        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val sizeField = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            val dataBytes =
                when (sizeField) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes

            when {
                // 全局项
                type == 1 && tag == 0x0 -> page = value // Usage Page
                type == 1 && tag == 0x1 -> { // Logical Minimum（有符号，按声明宽度补码）
                    logicalMin =
                        if (dataBytes in 1..3 && value and (1 shl (dataBytes * 8 - 1)) != 0) {
                            value - (1 shl (dataBytes * 8))
                        } else {
                            value
                        }
                }
                type == 1 && tag == 0x7 -> reportSize = value // Report Size
                type == 1 && tag == 0x9 -> reportCount = value // Report Count
                // 局部项：Usage（收进待分配列表；可能一次多项）
                type == 2 && tag == 0x0 -> pendingUsages += (page to value)
                // 主项：Input——bit0=Constant、bit1=Variable；消耗报告位
                type == 0 && tag == 0x8 -> flushInput(value)
                // 主项：Collection/EndCollection——HID 规范：每个 Main 项之后局部项清零，
                // 否则 Collection 自己的 Usage（如旋钮的 09 08）会错位占用第一个报告字段。
                type == 0 && (tag == 0xA || tag == 0xC) -> pendingUsages.clear()
            }
        }
        return if (fields.any { it.page == PAGE_GENERIC_DESKTOP && it.usage == USAGE_WHEEL }) {
            KnobMap(fields)
        } else {
            null
        }
    }
}
