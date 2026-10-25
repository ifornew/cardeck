package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.AppIcons
import io.github.shaonialife.cardeck.launcher.DefaultMapPrefs
import io.github.shaonialife.cardeck.launcher.DesktopApps
import io.github.shaonialife.cardeck.launcher.DockPrefs
import io.github.shaonialife.cardeck.launcher.SupersamplePrefs
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppRowsAdapter.AppHolder
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.util.Bg

/**
 * 车机设置内容面板（Fragment 化）：车机 SettingsActivity 双栏右栏、
 * 手机侧二级菜单页共用同一套面板实例，逻辑零拷贝。
 */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        adapter = DesktopAdapter()
        setupAppList(view, R.id.appList, R.id.appListIndex, adapter) {
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
        }
    }

    private fun updateCount() {
        countText?.text = "已选 ${selected.size} 个"
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) "「${entry.label}」已显示到桌面" else "「${entry.label}」已从桌面移除")
        adapter.notifyDataSetChanged()
    }

    private inner class DesktopAdapter : AppRowsAdapter(this@DesktopPanelFragment, R.layout.item_app_pick, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = PickerHolder(row)
    }

    private inner class PickerHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            check.setImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }
}

class SupersamplePanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_supersample, container, false)

    override fun onViewCreated(view: View) {
        setupAppList(view, R.id.ssList, R.id.ssListIndex, SupersampleAdapter())
    }

    private inner class SupersampleAdapter : AppRowsAdapter(this@SupersamplePanelFragment, R.layout.item_app_ss, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = SsHolder(row)
    }

    private inner class SsHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            refresh(SupersamplePrefs.mode(requireContext(), entry.component))

            fun pick(
                mode: Int,
                label: String,
            ) {
                SupersamplePrefs.setMode(requireContext(), entry.component, mode)
                refresh(mode)
                hostToast("「${entry.label}」超采样：$label（下次启动生效）")
            }
            segAuto.setOnClickListener { pick(SupersamplePrefs.MODE_AUTO, "自动") }
            segOn.setOnClickListener { pick(SupersamplePrefs.MODE_ON, "开启") }
            segOff.setOnClickListener { pick(SupersamplePrefs.MODE_OFF, "关闭") }
        }

        private fun refresh(mode: Int) {
            val segs =
                listOf(
                    segAuto to SupersamplePrefs.MODE_AUTO,
                    segOn to SupersamplePrefs.MODE_ON,
                    segOff to SupersamplePrefs.MODE_OFF,
                )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.background = if (active) requireActivity().getDrawable(R.drawable.bg_segment_sel) else null
                view.setTextColor(
                    if (active) {
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.car_segment_sel_text,
                        )
                    } else {
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.car_text_tertiary,
                        )
                    },
                )
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    /**
     * 系统侧真正开着覆盖的包集合（面板打开时后台查一次，行绑定只做内存查表——
     * `dumpsys platform_compat` 单次约 30ms/36KB，绝不可每行一次）。
     * 打开面板/清除残留后刷新；行内开关点击就地更新，避免重查。
     */
    private var systemOn: Set<String> = emptySet()
    private var systemStateLoaded = false
    private lateinit var listAdapter: OrientationAdapter
    private lateinit var panelView: View

    /**
     * 本机是否支持「强制横屏」——null = 探测中。
     *
     * 两条通路任一可用即视为支持：per-app ChangeId（Android 14+，荣耀 Android 15
     * 注册了、红米 Android 13 没注册）或 freeform 自由窗口启动（Android 12 起，
     * 华为 EMUI 14.2/SDK31 实车实测铺满）。都不支持时整列置灰并给出原因，
     * 否则用户会反复点开关、每次只得到失败提示（2026-10-18 实测）。
     */
    private var supported: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        panelView = view
        listAdapter = OrientationAdapter()
        // 应用清单就绪后重算横幅（首帧 apps 为空，全部会被误判成「不在列表」）
        setupAppList(view, R.id.oriList, R.id.oriListIndex, listAdapter) { updateOrphanBanner() }
        // 能力探测：dumpsys（约 30ms，结果进程内缓存）+ freeform 版本门槛，与系统状态查询并行
        Bg.run("orientation-capability") {
            val ok = AppOrientationCompat.changeIdsRegistered() || AppOrientationCompat.freeformLaunchSupported()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                supported = ok
                applyCapabilityState()
                listAdapter.notifyDataSetChanged()
            }
        }
        Bg.run("orientation-system-state") {
            val state = AppOrientationCompat.systemEnabled()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                systemOn = state
                systemStateLoaded = true
                listAdapter.notifyDataSetChanged()
                updateOrphanBanner()
            }
        }
    }

    /**
     * 按能力探测结果调整面板：不支持时显示横幅、整列变暗且不可点。
     *
     * 置灰而不是隐藏列表：用户能看到「这台机器做不到」，而不是面对一个消失的功能
     * 猜测原因。横幅文案说明是 ROM 缺项、换机型才能生效（避免误以为是 Shizuku 问题）。
     */
    private fun applyCapabilityState() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriUnsupportedBanner) ?: return
        val list = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.oriList)
        val index = panelView.findViewById<LinearLayout>(R.id.oriListIndex)
        if (supported != false) {
            banner.visibility = View.GONE
            list?.alpha = 1f
            index?.alpha = 1f
            return
        }
        banner.visibility = View.VISIBLE
        banner.text =
            "本机系统不支持该功能：需要 Android 12 及以上。\n" +
            "当前 Android ${android.os.Build.VERSION.RELEASE}" +
            "（SDK ${android.os.Build.VERSION.SDK_INT}），开关置灰不可用。"
        // 整列变暗表达「不可操作」；点击事件在 OriHolder 里按 supported 拦截
        list?.alpha = 0.38f
        index?.alpha = 0.38f
    }

    /**
     * 面板外残留（清单里点不到、系统侧却开着覆盖的键）横幅：点击弹窗确认后清理。
     * 包括两类：已卸载应用（覆盖只在 rawOverrides 可见）、以及历史脏数据里的
     * `包/组件` 键——这些在应用列表里都没有对应行，行内小字覆盖不到。
     */
    private fun updateOrphanBanner() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriOrphanBanner) ?: return
        // 应用清单未就绪时先不判定：apps 为空会让所有已开启覆盖都「看起来像列表外残留」
        // （列表真正装载后 onAppsReady 会再调一次）
        if (apps.isEmpty()) return
        val known = apps.map { it.component.packageName }.toSet()
        val orphans = systemOn.filter { it !in known }.sorted()
        if (orphans.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        // 组件键（含 /）很长：显示时截到包名部分，完整键留给弹窗
        val summary =
            orphans.take(3).joinToString("、") { it.substringBefore('/') } +
                if (orphans.size > 3) " 等 ${orphans.size} 个" else ""
        banner.text = "另有 ${orphans.size} 个列表外的横屏残留：$summary\n点击清除"
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity = host,
                title = "清除横屏残留",
                message =
                    "以下条目已不在应用列表，但系统侧横屏覆盖仍开着：\n" +
                        orphans.joinToString("\n") { "· $it" } +
                        "\n\n清除会立即重启在运行的这些应用（未保存的界面状态会丢失）；" +
                        "已卸载的条目不受影响。是否继续？",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                            Bg.run("orientation-orphan-clear") {
                                val cleared = orphans.count { AppOrientationCompat.clearSystemOnly(it) }
                                activity?.runOnUiThread {
                                    if (!isAdded || isDetached) return@runOnUiThread
                                    hostToast("已清除 $cleared/${orphans.size} 个横屏残留")
                                    systemOn = systemOn - orphans.toSet()
                                    listAdapter.notifyDataSetChanged()
                                    updateOrphanBanner()
                                }
                            }
                        },
                    ),
            )
        }
    }

    private inner class OrientationAdapter :
        AppRowsAdapter(this@OrientationPanelFragment, R.layout.item_orientation_car, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = OriHolder(row)
    }

    private inner class OriHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val hint: TextView = row.findViewById(R.id.oriHint)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        override fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            // 界面勾选状态即事实源：点击 = 取反当前显示状态（不重新读偏好，
            // 避免读值失真导致方向算反——实测「取消」会变成再次「启用」）
            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                // 面板顶部已探测到本机不支持（ROM 未注册 ChangeId）：直接忽略点击，
                // 不再发起注定失败的 shell 往返，也不用 toast 反复打扰。
                if (supported == false) return@setOnClickListener
                // **异步**：setEnabled 内部是 2 次 shell（各 4s 超时，最坏 8s），
                // 直接同步调用会把点击回调所在的主线程卡到 ANR。
                val target = !shown
                shown = target
                refresh(shown) // 立即回显（乐观更新），失败时回滚
                AppOrientationCompat.setEnabledAsync(requireContext(), pkg(), target) { result ->
                    if (!isAdded || isDetached) return@setEnabledAsync
                    when (result) {
                        AppOrientationCompat.WriteResult.OK -> {
                            // 就地更新系统侧缓存（写入已同步完成，无需重查 dumpsys）
                            systemOn = if (target) systemOn + pkg() else systemOn - pkg()
                            refresh(target)
                            // am compat 写入即生效：系统当场杀掉目标应用，返回时按新方向重启
                            // （2026-10-15 实测：enable/disable 后 pid 立即消失）。旧的「查活 +
                            // 询问是否结束」流程问的是已被系统杀掉的应用，pidof 又与击杀竞态、
                            // 提示时对时错（「未在运行」误导），整体移除、如实告知即可。
                            hostToast("「${entry.label}」横屏：${if (target) "开" else "关"}——应用将以新设置重启")
                        }

                        AppOrientationCompat.WriteResult.UNSUPPORTED -> {
                            shown = !target
                            refresh(shown)
                            supported = false
                            applyCapabilityState()
                            hostToast(
                                if (AppOrientationCompat.changeIdsRegistered()) {
                                    "系统限制：user 版本只能修改可调试(debuggable)应用的兼容设置"
                                } else {
                                    "本机 ROM 未注册横屏兼容项"
                                },
                            )
                        }

                        AppOrientationCompat.WriteResult.FAILED -> {
                            shown = !target
                            refresh(shown)
                            hostToast("写入失败（Shizuku 未就绪或超时）——可稍后重试")
                        }
                    }
                }
            }
        }

        private fun pkg() = entry.component.packageName

        /**
         * 勾选框 + 系统侧状态小字。三种状态：
         *   勾选开 + 系统开 → 灰字「已开启横屏（会话期间生效）」（正常）
         *   勾选关 + 系统开 → 红字高亮「系统残留：横屏仍在生效，点击清除」（异常，可点清除）
         *   其它            → 隐藏（勾选框本身已表达偏好，不制造噪音）
         */
        private fun refresh(enabled: Boolean) {
            check.setImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            val onSystem = systemStateLoaded && pkg() in systemOn
            when {
                onSystem && enabled -> {
                    hint.text = "已开启横屏（会话期间生效）"
                    hint.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.car_text_tertiary,
                        ),
                    )
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }

                onSystem -> {
                    hint.text = "系统残留：横屏仍在生效 · 点击清除"
                    hint.setTextColor(0xFFFF6B6B.toInt())
                    hint.visibility = View.VISIBLE
                    hint.isClickable = true
                    // 只清系统侧覆盖、不动偏好（偏好本来就是关的）；弹窗提示会重启应用
                    hint.setOnClickListener {
                        val host = activity ?: return@setOnClickListener
                        DarkMessageDialog.show(
                            activity = host,
                            title = "清除横屏残留",
                            message =
                                "「${entry.label}」的系统侧横屏覆盖仍在生效（可能是卸载前的残留）。" +
                                    "\n\n清除会立即重启该应用（正在运行的应用会丢失未保存的界面状态）。是否继续？",
                            actions =
                                listOf(
                                    DarkMessageDialog.Action("取消") {},
                                    DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                                        Bg.run("orientation-row-clear") {
                                            val ok = AppOrientationCompat.clearSystemOnly(pkg())
                                            activity?.runOnUiThread {
                                                if (!isAdded || isDetached) return@runOnUiThread
                                                if (ok) {
                                                    systemOn = systemOn - pkg()
                                                    refresh(shown)
                                                    hostToast("「${entry.label}」横屏残留已清除——应用将重启")
                                                } else {
                                                    hostToast("清除失败（Shizuku 异常）")
                                                }
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }

                else -> {
                    hint.visibility = View.GONE
                    hint.setOnClickListener(null)
                }
            }
        }
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val scrimSeek = view.findViewById<SeekBar>(R.id.dockScrimSeek)
        val scrimValue = view.findViewById<TextView>(R.id.dockScrimValue)
        val blurSeek = view.findViewById<SeekBar>(R.id.dockBlurSeek)
        val blurValue = view.findViewById<TextView>(R.id.dockBlurValue)
        val splitSeek = view.findViewById<SeekBar>(R.id.dockSplitSeek)
        val splitValue = view.findViewById<TextView>(R.id.dockSplitValue)

        scrimSeek.progress =
            DockPrefs
                .scrimOpacity(requireContext())
        scrimValue.text = "${scrimSeek.progress}%"
        scrimSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scrimValue.text = "$value%"
                    DockPrefs
                        .setScrimOpacity(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        blurSeek.progress =
            DockPrefs
                .blur(requireContext())
        blurValue.text = "${blurSeek.progress}%"
        blurSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    blurValue.text = "$value%"
                    DockPrefs
                        .setBlur(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        // 仪表盘分屏比例：SeekBar 范围 0..25 映射到 50..75%（DockPrefs.splitRatio 存真实百分比）
        splitSeek.progress = DockPrefs.splitRatio(requireContext()) - 50
        splitValue.text = "${DockPrefs.splitRatio(requireContext())}%"
        splitSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val percent = value + 50
                    splitValue.text = "$percent%"
                    DockPrefs.setSplitRatio(requireContext(), percent)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        // 导航快捷入口：家/单位地址（失焦或回车保存）。地图 App 跟随「默认地图」设置
        val homeInput = view.findViewById<EditText>(R.id.navHomeInput)
        val workInput = view.findViewById<EditText>(R.id.navWorkInput)
        homeInput.setText(DockPrefs.navHomeAddress(requireContext()))
        workInput.setText(DockPrefs.navWorkAddress(requireContext()))
        homeInput.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    DockPrefs.setNavHomeAddress(requireContext(), s?.toString() ?: "")
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            },
        )
        workInput.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    DockPrefs.setNavWorkAddress(requireContext(), s?.toString() ?: "")
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            },
        )
    }
}

class DefaultMapPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.mapOptions)
        val installed =
            VirtualLauncher.MAP_PACKAGES_PRIORITY.mapNotNull { pkg ->
                val launch =
                    requireContext().packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@mapNotNull null
                val label =
                    runCatching {
                        requireContext()
                            .packageManager
                            .getApplicationInfo(pkg, 0)
                            .loadLabel(requireContext().packageManager)
                            .toString()
                    }.getOrDefault(pkg)
                pkg to label
            }
        if (installed.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "未安装任何受支持的地图应用（高德/百度/腾讯）"
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.car_text_tertiary,
                        ),
                    )
                    textSize = 13f
                },
            )
            return
        }
        var current =
            DefaultMapPrefs.manualPackage(requireContext())
                ?: installed.firstOrNull()?.first
        installed.forEach { (pkg, label) ->
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            val appIcon =
                runCatching {
                    requireContext().packageManager.getApplicationIcon(pkg)
                }.getOrNull()
            if (appIcon != null) {
                row
                    .findViewById<ImageView>(R.id.mapOptionIcon)
                    .setImageBitmap(AppIcons.rounded(appIcon, iconPx))
            }
            row.findViewById<TextView>(R.id.mapOptionName).text = label
            val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)

            fun refresh() {
                radio.setImageResource(
                    if (pkg == current) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                )
            }
            refresh()
            row.setOnClickListener {
                current = pkg
                DefaultMapPrefs.set(requireContext(), pkg)
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i).tag as? (() -> Unit))?.invoke()
                }
                hostToast("默认地图：$label")
            }
            row.tag = { refresh() }
            container.addView(row)
        }
    }
}

/**
 * 「显示」面板：流分辨率缩放（30%–160%，下次连接起效）+ 目标帧率（30/60，
 * 运行中实时切换）。英雄卡展示的是会话运行时生效值（SessionController 快照），
 * 与本页偏好值相区别。
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    /** 车机能力轮询（握手晚于设置页打开时靠它刷新档位可用性；视图销毁时移除）。 */
    private var fpsPollRef: Runnable? = null

    override fun onDestroyView() {
        fpsPollRef?.let { view?.removeCallbacks(it) }
        fpsPollRef = null
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)
        val fps90 = view.findViewById<TextView>(R.id.btnFps90)
        val fps120 = view.findViewById<TextView>(R.id.btnFps120)

        // 滑条 progress（0..26）→ 百分比（30..160，步进 5）
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    "保存后下次连接起效；当前连接按 " +
                        "${SessionController.activeScalePercent}% 运行"
                } else {
                    "保存后下次连接起效"
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast("分辨率 $percent% 已保存，下次连接起效")
                    refreshScaleNote()
                }
            },
        )

        val fpsNote = view.findViewById<TextView>(R.id.displayFpsNote)

        // 车机能力上限（握手后可知；0=未知）：高于上限的档位禁用——SessionController
        // 建流时会把它们夹紧，选着也只会白增编码开销与无效帧（2026-10-25 用户要求）。
        fun carLimit() = SessionController.carMaxFps.takeIf { it in 24..240 } ?: 0

        fun refreshFps(active: Int) {
            val limit = carLimit()
            // 偏好可能是在别的车机（更高上限）上存的：这里高亮**实际生效**的档位，
            // 否则会出现「四个都没选中」的空档（SessionController 建流时同样会夹紧）。
            val effective = if (limit > 0 && active > limit) limit else active
            // 未选中片用暗色底（与容器同色）而非 null 背景，避免背景重置内边距
            listOf(fps30 to 30, fps60 to 60, fps90 to 90, fps120 to 120).forEach { (chip, value) ->
                val supported = limit == 0 || value <= limit
                val selected = value == effective && supported
                chip.isEnabled = supported
                chip.alpha = if (supported) 1f else 0.32f
                chip.setBackgroundResource(
                    if (selected) R.drawable.bg_segment_sel else R.drawable.bg_segment,
                )
                chip.setTextColor(
                    if (selected) {
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.car_segment_sel_text)
                    } else {
                        androidx.core.content.ContextCompat.getColor(
                            ctx,
                            R.color.car_text_tertiary,
                        )
                    },
                )
            }
            fpsNote.text =
                if (limit > 0) {
                    "当前车机最高支持 ${limit}fps，更高档位已禁用。连接中切换实时生效；实际帧率还取决于编码器余量"
                } else {
                    "连接中切换实时生效；车机握手识别后会自动禁用其不支持的高档位。90/120 fps 为实验档：实际帧率取决于编码器余量，功耗发热更高"
                }
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps)

        fun applyFps(fps: Int) {
            val limit = carLimit()
            if (limit > 0 && fps > limit) {
                hostToast("当前车机最高支持 ${limit}fps")
                return
            }
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps)
            VirtualLauncher
                .setStreamFps(fps)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast("帧率已切换为 ${fps}fps${if (live) "（实时生效）" else ""}")
            if (live) SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }
        fps90.setOnClickListener { applyFps(90) }
        fps120.setOnClickListener { applyFps(120) }

        // 设置页可能先于车机握手打开：每 2s 对账一次车机上限/运行时生效值，握手完成即
        // 立刻禁用不支持的档位（poll 随视图销毁移除）。
        val fpsPoll =
            object : Runnable {
                override fun run() {
                    if (!isAdded) return
                    refreshFps(DisplayPrefs.fps(requireContext()))
                    view.postDelayed(this, 2_000)
                }
            }
        fpsPollRef = fpsPoll
        view.postDelayed(fpsPoll, 2_000)

        // 触摸注入节流（0=关闭，步进 8ms）：缓解「交互滞后但帧率正常」的事件堆积
        val throttleSeek = view.findViewById<SeekBar>(R.id.throttleSeek)
        val throttleValue = view.findViewById<TextView>(R.id.throttleValue)

        fun throttleText(ms: Int) = if (ms <= 0) "关闭" else "${ms}ms"

        val savedThrottle = DisplayPrefs.moveThrottleMs(ctx)
        throttleSeek.progress = savedThrottle / 8
        throttleValue.text = throttleText(savedThrottle)
        TouchRouter.moveThrottleMs = savedThrottle.toLong()

        throttleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val ms = progress * 8
                    throttleValue.text = throttleText(ms)
                    DisplayPrefs.setMoveThrottleMs(ctx, ms)
                    TouchRouter.moveThrottleMs = ms.toLong()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    hostToast(
                        "触摸节流 " + throttleText(DisplayPrefs.moveThrottleMs(ctx)),
                    )
                }
            },
        )
    }
}

/**
 * 「外观主题」面板：自动 / 白天 / 黑夜三档（[ThemePrefs]）。
 * 自动档目前跟随手机系统深浅色，车机昼夜信号接入后自动优先车机信号。
 */
class ThemePanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_theme, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val auto = view.findViewById<TextView>(R.id.btnThemeAuto)
        val day = view.findViewById<TextView>(R.id.btnThemeDay)
        val night = view.findViewById<TextView>(R.id.btnThemeNight)

        fun refresh(active: Int) {
            listOf(auto to ThemePrefs.AUTO, day to ThemePrefs.DAY, night to ThemePrefs.NIGHT)
                .forEach { (chip, mode) ->
                    val selected = mode == active
                    chip.setBackgroundResource(
                        if (selected) R.drawable.bg_segment_sel else R.drawable.bg_segment,
                    )
                    chip.setTextColor(
                        if (selected) {
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.car_segment_sel_text)
                        } else {
                            androidx.core.content.ContextCompat.getColor(
                                ctx,
                                R.color.car_text_tertiary,
                            )
                        },
                    )
                }
        }

        fun pick(mode: Int) {
            if (ThemePrefs.mode(ctx) == mode) return
            // 切换会触发车机侧 Activity 重建（主题资源重载），先给出提示再应用
            hostToast(
                when (mode) {
                    ThemePrefs.DAY -> "外观：白天"
                    ThemePrefs.NIGHT -> "外观：黑夜"
                    else -> "外观：自动（跟随手机系统）"
                },
            )
            ThemePrefs.setMode(ctx, mode)
        }

        auto.setOnClickListener { pick(ThemePrefs.AUTO) }
        day.setOnClickListener { pick(ThemePrefs.DAY) }
        night.setOnClickListener { pick(ThemePrefs.NIGHT) }

        // 高德等镜像应用只读手机系统的深浅色：显式白天/黑夜时可选同步切系统
        val sync = view.findViewById<TextView>(R.id.btnThemeSyncSystem)
        fun refreshSync() {
            val on = ThemePrefs.syncSystemNight(ctx)
            sync.text = if (on) "同步系统深色：开" else "同步系统深色：关"
            sync.setBackgroundResource(if (on) R.drawable.bg_menu_item else R.drawable.bg_segment)
            sync.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx,
                    if (on) R.color.car_on_accent else R.color.car_text_primary,
                ),
            )
        }
        sync.setOnClickListener {
            val on = !ThemePrefs.syncSystemNight(ctx)
            ThemePrefs.setSyncSystemNight(ctx, on)
            refreshSync()
            hostToast(if (on) "已开启：切换白天/黑夜时同步手机系统深色（需 Shizuku）" else "已关闭系统深色同步")
        }
        refreshSync()

        refresh(ThemePrefs.mode(ctx))
    }
}
