// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.settings

object SettingGroups {

    const val ISLAND_ENABLED_KEY = "island.enabled"

    const val LYRIC = "歌词"

    const val LYRIC_COLOR_OUTLINE = "歌词颜色与描边"

    const val ISLAND = "词岛"

    const val ISLAND_APPEARANCE = "词岛外观"

    const val RIGHT_SLOT = "右侧小组件"

    const val SPECTRUM = "频谱"

    const val COVER = "封面"

    const val CONTROLS = "播放控件"

    const val ANIMATION = "动画"

    const val MENU_SETTINGS = "菜单与设置"

    val ALL: List<String> = listOf(
        LYRIC,
        LYRIC_COLOR_OUTLINE,
        ISLAND,
        ISLAND_APPEARANCE,
        RIGHT_SLOT,
        SPECTRUM,
        COVER,
        CONTROLS,
        ANIMATION,
        MENU_SETTINGS,
    )

    val SUBS: Map<String, List<String>> = mapOf(

        LYRIC to listOf("排版", "高亮", "字体", "译文", "无歌词"),
        LYRIC_COLOR_OUTLINE to listOf("填充", "歌词描边"),
        ISLAND to listOf("显示", "交互", "滚轮", "位置与尺寸"),
        ISLAND_APPEARANCE to listOf("形状与背景", "边框", "词岛材质"),
        RIGHT_SLOT to listOf("全部"),
        SPECTRUM to listOf("开关与位置", "条与高度", "外观", "进度色差"),
        COVER to listOf("开关与缩放"),
        CONTROLS to listOf("触发", "布局", "进度与隐藏"),
        ANIMATION to listOf("质量与帧率", "动效风格", "时长", "宽度生长"),
        MENU_SETTINGS to listOf("菜单外观", "菜单延迟", "行为"),
    )

    val ALL_SUBS: List<String> = ALL.flatMap { SUBS.getValue(it) }

}

object AnimationStyleResolver {

    const val FOLLOW_TOTAL = "FOLLOW"

    const val TOTAL_PER_COMPONENT = "PER_COMPONENT"

    val STYLES: List<String> = listOf("FADE", "SLIDE", "POP")

    fun componentOptions(): List<Pair<String, String>> = listOf(
        FOLLOW_TOTAL to "跟随总开关",
        "FADE" to "淡入",
        "SLIDE" to "滑入",
        "POP" to "弹出",
    )

    fun effective(
        componentValue: String?,
        totalValue: String?,
        factoryDefault: String,
    ): String {

        val total = totalValue?.trim()?.uppercase()
        if (total != null && total in STYLES) return total

        val component = componentValue?.trim()?.uppercase()
        if (component != null && component in STYLES) return component

        return factoryDefault
    }
}

object SettingRegistry {

    private fun groupA(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "island.enabled",

            group = SettingGroups.ISLAND,
            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "显示词岛",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(

            key = "playback.autoplay_on_start",
            group = SettingGroups.ISLAND,

            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "启动时自动播放",
            summary = "**插件启动时**若宿主已有播放队列，就自行开始播放。" +
                "★ 只影响**启动那一刻** —— 暂停之后**不会**自动恢复（那是宿主的播放器行为）。",
            default = true,

            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.interactive",
            group = SettingGroups.ISLAND,
            subGroup = "交互",
            kind = SettingKind.SWITCH,
            title = "鼠标穿透",
            summary = "**勾选后鼠标可穿过词岛**（词岛不再响应点击与拖动）；取消勾选则词岛可交互。若已穿透而想操作词岛，用右键菜单里的「找回词岛」。",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.hide_fullscreen",
            group = SettingGroups.ISLAND,
            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "全屏时隐藏",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.hide_paused",
            group = SettingGroups.ISLAND,
            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "暂停时隐藏",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.auto_hide_on_hover",
            group = SettingGroups.ISLAND,
            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "鼠标经过时自动隐藏",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.always_on_top",
            group = SettingGroups.ISLAND,
            subGroup = "显示",
            kind = SettingKind.SWITCH,
            title = "窗口置顶",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.lock_position",
            group = SettingGroups.ISLAND,
            subGroup = "交互",
            kind = SettingKind.SWITCH,
            title = "锁定位置",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "island.lock_collapsed",
            group = SettingGroups.ISLAND,
            subGroup = "交互",
            kind = SettingKind.SWITCH,
            title = "锁定收齐",
            summary = "锁定为收起状态：鼠标悬停时**不再展开**控件条。与「锁定位置」相互独立。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "island.hit.cover",
            group = SettingGroups.ISLAND,
            subGroup = "交互",
            kind = SettingKind.SWITCH,
            title = "封面参与鼠标命中",
            summary = "开启后，鼠标停在封面上与停在歌词上一样：词岛不穿透、可拖动，并会展开播放控件。",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "wheel.volume.enabled",
            group = SettingGroups.ISLAND,
            subGroup = "滚轮",
            kind = SettingKind.SWITCH,
            title = "滚轮调整音量",
            summary = "鼠标停在词岛上时，向上滚加音量、向下滚减音量。" +
                "调整的是系统「音量合成器」里本程序的音量（宿主未开放音量接口）。",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "wheel.volume.step_percent",
            group = SettingGroups.ISLAND,
            subGroup = "滚轮",
            kind = SettingKind.SEEKBAR,
            title = "滚轮每格音量 %",
            summary = "每拨一格滚轮，音量变化多少个百分点。5% 表示全程约 20 格。",

            default = 5,
            range = 1.0..20.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    private fun groupB(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "layout.translation.enabled",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.SWITCH,
            title = "显示翻译（副歌词）",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.translation.scale_percent",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.SEEKBAR,
            title = "副歌词字号（%）",

            summary = "副歌词相对主歌词的字号。100 = 标准（副歌词为主歌词的 64%）；越大副歌词越接近主歌词。",
            default = 100,
            range = 50.0..150.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "main_lyric.outline.enabled",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "主歌词描边",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.width_tenth_px",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边宽度（0.1 像素）",
            default = 10,
            range = 0.0..40.0,
            summary = "仅在开启描边时生效。10 = 1.0 像素（描边在字形外侧的可见厚度约为其一半）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.follow_font_weight",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "描边粗细跟随字体粗细",

            summary = "开启后，字体变粗/变细会按下面的比例带动描边宽度。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.weight_link_percent",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "描边联动比例（%）",

            summary = "描边跟随字体粗细的程度。0 = 完全不跟随；50 = 半跟随（推荐，中值）；100 = 等比跟随。",
            default = 50,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 R",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 G",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.outline.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "主歌词描边色 B",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.sync_with_main",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边跟随主歌词",

            default = true,
            summary = "开启后副歌词描边完全跟随主歌词；关闭后可用下方各项单独设置。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.enabled",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.width_tenth_px",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边宽度（0.1 像素）",
            default = 10,
            range = 0.0..40.0,
            summary = "仅在开启描边时生效。10 = 1.0 像素（描边在字形外侧的可见厚度约为其一半）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "translation.outline.follow_weight",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SWITCH,
            title = "副歌词描边跟随字体粗细",
            summary = "开启后，副歌词描边宽度随「字体粗细」按「描边联动比例」变化；" +
                "关闭时恒用上面的「副歌词描边宽度」。仅在关闭「副歌词描边跟随主歌词」时生效。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 R",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 G",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "translation.outline.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "歌词描边",
            kind = SettingKind.SEEKBAR,
            title = "副歌词描边色 B",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.translation.position",
            group = SettingGroups.LYRIC,
            subGroup = "译文",
            kind = SettingKind.LIST,
            title = "副歌词位置",

            default = "BELOW",
            summary = "仅在「歌词行数」为双行且开启了翻译时生效。",
            options = listOf(
                "BELOW" to "主歌词下方（默认）",
                "ABOVE" to "主歌词上方",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.karaoke.mode",
            group = SettingGroups.LYRIC,
            subGroup = "高亮",
            kind = SettingKind.LIST,
            title = "歌词高亮方式",

            default = "PER_CHAR",
            summary = "三档互斥：整行高亮 / 平滑 / 逐字。有逐字时间轴时「逐字」档以时间轴为准。",
            options = listOf(
                "LINE" to "不逐字（整行高亮）",
                "SMOOTH" to "平滑高亮（已读色在字间移动）",
                "PER_CHAR" to "逐字高亮",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    private fun groupC(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "window.position",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.EDITTEXT,
            title = "词岛位置",

            default = "",
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.max_width",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SEEKBAR,
            title = "最大宽度",
            summary = "开启「固定词岛宽度」时它决定词岛宽度；关闭时它是宽度上限（按歌词长度收缩到此值以内）。",
            default = 640,

            range = 280.0..2400.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.fixed_width",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SWITCH,
            title = "固定词岛宽度",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "layout.island.height.follow_font",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SWITCH,
            title = "词岛高度跟随字号",
            summary = "关闭后可手动指定高度（相对歌词行总高的比值）。",

            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.island.height.ratio_percent",
            group = SettingGroups.ISLAND,
            subGroup = "位置与尺寸",
            kind = SettingKind.SEEKBAR,
            title = "词岛高度比例",

            summary = "词岛收起态高度 ÷ 歌词行总高（含译文行）。仅在关闭「词岛高度跟随字号」时生效。" +
                "100% = 恰好等于歌词块。",

            default = 120,

            range = 100.0..250.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.content_align",
            group = SettingGroups.LYRIC,
            subGroup = "排版",
            kind = SettingKind.LIST,

            title = "歌词对齐",

            default = "CENTER",
            options = listOf("LEFT" to "左对齐", "CENTER" to "居中", "RIGHT" to "右对齐"),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.lines",
            group = SettingGroups.LYRIC,
            subGroup = "排版",
            kind = SettingKind.LIST,
            title = "歌词行数",

            default = "SINGLE",
            summary = "双行时第二行为副歌词，仍受「显示译文行」开关控制。",
            options = listOf(
                "SINGLE" to "单行（仅主歌词）",
                "DUAL" to "双行（主歌词 + 副歌词）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "layout.line_gap_px",
            group = SettingGroups.LYRIC,
            subGroup = "排版",
            kind = SettingKind.SEEKBAR,
            title = "歌词行间距",

            default = 0,
            range = 0.0..80.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    private fun groupD(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "shape.type",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "外观形态",

            default = "SQUARE",
            options = listOf(
                "SQUARE" to "方形",
                "ROUNDED_RECT" to "圆角矩形",
                "PILL" to "胶囊",

            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "shape.corner_radius",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "圆角大小 %",

            summary = "仅在形态为「圆角矩形」时生效；选「方形」「胶囊」时本项被忽略。",

            default = 60,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "shape.corner_style",

            retired = true,
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "角曲率风格",
            summary = "「圆弧角」= 现有行为；「连续曲率」= 超椭圆（squircle）角。",

            default = "ARC",
            options = listOf(
                "ARC" to "圆弧角",
                "SQUIRCLE" to "连续曲率",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "shape.corner_n",

            retired = true,
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "连续曲率指数 n",
            summary = "仅在角曲率风格为「连续曲率」时生效。4 = 经典 squircle；越大角越方。",

            default = 4,
            range = 2.0..10.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "background.opacity",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "背景不透明度 %",
            default = 96,

            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "background.color",

            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.COLOR,
            title = "背景颜色",

            default = 0xFF14161A.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,

            summary = "背景颜色（背景颜色来源选「手动指定」时生效）",
        ),

        SettingDescriptor(
            key = "background.cover.angle_deg",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.SEEKBAR,
            title = "封面图旋转角度",
            summary = "仅在「背景颜色来源」选「封面图」时生效。旋转后自动重新等比缩放铺满词岛，无需另设缩放。",

            default = 0,
            range = 0.0..360.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "background.color_mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "形状与背景",
            kind = SettingKind.LIST,
            title = "背景颜色来源",

            default = "manual",
            options = listOf(
                "manual" to "手动指定",
                "cover" to "跟随封面取色",

                "cover_image" to "封面图",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "material.highlight.strength",

            retired = true,
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "顶部高光强度",
            summary = "从顶部向下淡出的白色高光（玻璃厚度感）。0 = 关闭。",

            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,

        ),
        SettingDescriptor(
            key = "material.fill.gradient_style",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.LIST,
            title = "背景渐变",
            summary = "「关闭」= 现有纯色背景；「线性」= 沿指定角度的线性渐变；"
                + "「封面多色流光」= 从封面取**多个**颜色做多停靠点渐变（颜色随每首歌变）。",

            default = "NONE",
            options = listOf(
                "NONE" to "关闭",
                "LINEAR" to "线性",

                "FLOW" to "封面多色流光",

                "CAMO" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "material.fill.flow_speed",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "流光速度",
            summary = "「封面多色流光 / 封面迷彩碎色流光」的流动速度（% 原速）：" +
                "0 = 静止，±50 = 半速，负值 = 反向；默认 15 = 原速的 15%（背景与频谱两条流光同比例缩放）。",
            default = 15,
            range = -50.0..50.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.gradient_tint_percent",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "渐变强度 %",
            summary = "起色向止色混合的比例。0 = 不画渐变（走纯色路径）。",

            default = 30,
            range = 0.0..60.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.gradient_angle_deg",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "渐变角度 °",

            summary = "0° = 从左到右，90° = 从上到下（顺时针）。",

            default = 90,
            range = 0.0..360.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.fill.color_end",

            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.COLOR,
            title = "渐变止色",

            summary = "渐变的另一端颜色。仅在「背景渐变」开启后生效。",

            default = 0xFF2A2E38.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
        ),

        SettingDescriptor(
            key = "glass.blur_radius",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "玻璃 · 模糊半径",
            default = 10,
            range = 0.0..20.0,
            summary = "越大越糊。通透材质下 10 已是「能看清结构」的上限。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "glass.clarity",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.LIST,
            title = "玻璃 · 清晰度",
            default = "HIGH",
            summary = "满 = 不降采样，最清晰但每帧成本最高（通透材质下可能掉到 2Hz 左右）；标准 = 降采样 2 倍，10Hz 的默认档。",
            options = listOf(
                "MAX" to "满（最清晰，性能代价大）",
                "HIGH" to "高（默认）",
                "STANDARD" to "标准（最省）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "glass.dispersion",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "玻璃 · 色散",
            default = 35,
            range = 0.0..100.0,
            summary = "边缘的彩边强度（棱镜色差）。0 = 无色散。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "glass.saturation",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "玻璃 · 饱和度",
            default = 150,
            range = 0.0..200.0,
            summary = "透过玻璃看到的颜色浓度。100 = 原样。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "glass.surface_alpha",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "玻璃 · 表面不透明度",
            default = 10,
            range = 0.0..30.0,
            summary = "玻璃表面那层「膜」的强度。越大越像磨砂、越不透。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "glass.material",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.LIST,
            title = "玻璃材质",

            default = "NONE",
            summary = "「关闭」= 不启用液态玻璃（默认）；「通透玻璃」保留背后结构；" +
                "「磨砂玻璃」更柔和，性能开销约为前者的 1/20。",
            options = listOf(
                "NONE" to "关闭",
                "LIQUID" to "通透玻璃（液态）",
                "FROSTED" to "磨砂玻璃（亚克力）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "material.liquid.glare_strength",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 高光强度",

            summary = "液态玻璃的高光（Glare）强度。100 = 最强；建议 40–70，过高会过曝发糊。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.liquid.depth_effect",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SWITCH,
            title = "液态玻璃 · 立体感",

            summary = "开启后玻璃有边缘厚度感（菲涅耳边缘光）；关闭则只剩高光，更扁平。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),

        SettingDescriptor(
            key = "material.liquid.surface_alpha",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 表面蒙版",

            summary = "玻璃表面再铺一层极淡白，让面板更「实」。100 = 最实；建议 5–20，过高会发灰。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.liquid.shadow_strength",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "液态玻璃 · 投影强度",

            summary = "词岛下方的柔和阴影，让面板「浮」在桌面上。100 = 最深；建议 40–80，过高会发黑。",
            default = 0,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
            retired = true,
        ),
        SettingDescriptor(
            key = "material.noise.amplitude",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "颗粒幅度",
            summary = "背景噪点（磨砂颗粒感）。0 = 关闭。",
            default = 0,
            range = 0.0..20.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "material.quality",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.LIST,
            title = "材质画质档",

            summary = "「关闭」= 不画材质的高光 / 渐变 / 噪点三层（「封面图模糊」仍按它自己的开关绘制）；" +
                "「精简」/「完整」= 都画全部材质，差别在模糊的成本路径。",

            default = "FULL",
            options = listOf(
                "OFF" to "关闭",
                "LITE" to "精简",
                "FULL" to "完整",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,

        ),
        SettingDescriptor(
            key = "material.content.blur_radius",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SEEKBAR,
            title = "封面图模糊",
            summary = "模糊岛体自己的封面图背景（不采桌面）。0 = 关闭。它不是「身后窗口模糊」（那个要采桌面，见 L3）。",

            default = 0,
            range = 0.0..40.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "material.render.gpu_backend",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "词岛材质",
            kind = SettingKind.SWITCH,
            title = "身后窗口 · 用显卡算折射",

            summary = "把液态玻璃折射交给显卡计算（更快）。关闭则用 CPU。仅 Windows 有效；显卡不支持时自动回退 CPU。" +
                "渲染负载实测（帧预算 16.7 ms / 60 fps）：" +
                "· 词岛玻璃逐像素 13.6 ms/次（单线程），已分片并行、加速上限 4.16×；" +
                "· 背景折射整轮 ≈33.4 ms = 截屏 8.25 + 判据 2.6 + 模糊 5.9 + 折射 16.6（折射占 ≈50%），按 10 Hz 采集、约占单核 33.4%；" +
                "· 采集线程已降到最低优先级 ⇒ 独显提升不明显，是因玻璃那部分已在 CPU 分片并行到接近上限。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,

            retired = true,
        ),
    )

    private fun groupE(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "main_lyric.font.family",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.FONT,
            title = "字体",

            default = "@host:MiSans",
            phase = SettingPhase.P1,

            preview = PreviewKind.DEFERRED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.size",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.SEEKBAR,
            title = "字号",
            default = 22,

            range = 8.0..200.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.weight",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.LIST,
            title = "字体粗细",

            summary = "常规 = 改动前的观感；加粗 = 用**该字体自己的粗体**" +
                "（多字重字体会挑到真字重，单字重字体走算法合成）。",
            default = "NORMAL",
            options = listOf(
                "NORMAL" to "常规",
                "BOLD" to "加粗",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "main_lyric.font.recent",
            group = SettingGroups.LYRIC,
            subGroup = "字体",
            kind = SettingKind.LIST,
            title = "最近使用字体",

            default = "",

            options = emptyList(),
            phase = SettingPhase.P1,
            preview = PreviewKind.DEFERRED,
        ),
    )

    private fun groupF(): List<SettingDescriptor> = listOf(

        SettingDescriptor(
            key = "main_lyric.fill.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 红",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 绿",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 蓝",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.color.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "已唱歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 红",
            default = 126,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 绿",
            default = 129,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 蓝",
            default = 138,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.fill.unsung.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "未唱歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.r",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 红",
            default = 177,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.g",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 绿",
            default = 182,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.b",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 蓝",
            default = 195,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color.lightness",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.SEEKBAR,
            title = "译文歌词颜色 · 明度",
            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
    )

    private fun groupG(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "spectrum.enabled",

            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.SWITCH,
            title = "显示实时频谱",

            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.enabled",

            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "显示专辑封面",
            summary = "关闭后词岛不显示专辑封面（歌词、频谱与播放控件不受影响）。",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.scale_with_font",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "封面随字号缩放",
            summary = "仅在开启「显示专辑封面」时生效。",

            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.sharpness",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SEEKBAR,
            title = "封面清晰度（锐化）",
            summary = "仅在开启「显示专辑封面」时生效。0 = 只做高质量缩放；" +
                "值越大细节越锐，过高会在锐边处出现光晕。",

            default = 25,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.shape",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.LIST,
            title = "封面形状",

            default = "ROUNDED",
            summary = "选「圆形」再开「封面转动」，就是黑胶唱片效果。",

            options = listOf(
                "ROUNDED" to "圆角（默认）",
                "CIRCLE" to "圆形",

                "SQUARE" to "方形",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "cover.rotate",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "封面转动",

            default = false,
            summary = "按播放进度匀速旋转（暂停即停）。配「封面形状 = 圆形」是黑胶唱片效果。",
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "cover.rotate_period_s",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SEEKBAR,
            title = "封面转动一圈用时（秒）",

            default = 20,
            range = 4.0..120.0,
            summary = "仅在开启「封面转动」时生效。数值越小转得越快。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.vinyl",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "黑胶样式（外围黑环）",

            default = false,
            summary = "在封面外围加一圈黑环（不遮住封面本身）。配「圆形 + 转动」是黑胶唱片效果。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "cover.lock",
            group = SettingGroups.COVER,
            subGroup = "开关与缩放",
            kind = SettingKind.SWITCH,
            title = "锁定封面（悬浮窗形态）",

            default = false,
            summary = "【已下架 2026-10-17】仅在「外观形态 = 悬浮窗」时生效；该形态已从设置面撤下，" +
                "本项随之隐藏（值不再影响渲染，恒为未锁）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,

            retired = true,
        ),
        SettingDescriptor(
            key = "main_lyric.color_mode",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.LIST,
            title = "主歌词颜色来源",
            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "main_lyric.translation.color_mode",
            group = SettingGroups.LYRIC_COLOR_OUTLINE,
            subGroup = "填充",
            kind = SettingKind.LIST,
            title = "副歌词颜色来源",

            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "animation.enabled",

            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.SWITCH,
            title = "启用动画",
            summary = "关闭后所有过渡与重效果立即消失。控件条的悬停淡入是既有行为，不受本开关影响。",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "animation.quality",
            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.LIST,
            title = "动画质量",

            summary = "决定**开启哪些动画效果**。标准 = 仅基础过渡；高 = 加歌词行退场；极致 = 再加频谱峰值保持。帧率由「动画帧率」单独设置。",
            default = "HIGH",
            options = listOf(
                "STANDARD" to "标准（仅基础过渡）",
                "HIGH" to "高（基础 + 歌词行退场）",
                "ULTRA" to "极致（全部 + 频谱峰值保持）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.frame_rate",
            group = SettingGroups.ANIMATION,
            subGroup = "质量与帧率",
            kind = SettingKind.LIST,
            title = "动画帧率",

            summary = "动画帧率上限。自动 = 跟随显示器刷新率（推荐）。各档取「不超过该值的最高可达帧率」（显示器刷新率的整数分之一），实际值可能低于档位数字、但绝不会超出；CPU 占用偏高时可降到 60。",
            default = "AUTO",
            options = listOf(
                "AUTO" to "自动（跟随显示器刷新率）",
                "60" to "不超过 60 Hz（最省电）",
                "90" to "不超过 90 Hz",
                "120" to "不超过 120 Hz",
                "144" to "不超过 144 Hz（最顺滑）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.duration_scale",
            group = SettingGroups.ANIMATION,
            subGroup = "时长",
            kind = SettingKind.SEEKBAR,
            title = "动画时长（%）",
            summary = "50 = 干脆利落，100 = 默认，200 = 舒缓，1000 = 放慢 10 倍。只改时长、不改效果数量；作用于词岛自身的过渡（显隐 / 封面 / 歌词行），菜单与控件条有各自的即时反馈节奏，不跟随。⚠️ 数值越大，动画持续重绘越久 —— 切歌等场景会出现数秒的「卡顿感」（实测 1000 时约 2.6 秒）。",
            default = 100,

            range = 50.0..1000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "animation.width_grow.enabled",
            group = SettingGroups.ANIMATION,
            subGroup = "宽度生长",
            kind = SettingKind.SWITCH,
            title = "启用宽度生长动画",
            summary = "切歌时歌词长短变化 ⇒ 词岛宽度**平滑过渡**（而不是瞬间跳变）。" +
                "关闭后回到「瞬间切换」，性能最好。",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.width_grow.duration_ms",
            group = SettingGroups.ANIMATION,
            subGroup = "宽度生长",
            kind = SettingKind.SEEKBAR,
            title = "宽度生长时长（毫秒）",
            summary = "80 = 几乎看不出过渡，220 = 默认，500 = 明显的「伸展」感。" +
                "⚠️ 不受「动画时长（%）」缩放 —— 那个只作用于 alpha/位置类动效。",
            default = 220,
            range = 80.0..500.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "animation.width_grow.style",
            group = SettingGroups.ANIMATION,
            subGroup = "宽度生长",
            kind = SettingKind.LIST,
            title = "宽度生长曲线",
            summary = "平滑 = 两端缓、中段快（默认）；线性 = 匀速；" +
                "先快后慢 = 起步迅速、结尾柔和（适合「由长变短」）。",
            default = "SMOOTH",
            options = listOf(
                "SMOOTH" to "平滑（smoothstep）",
                "LINEAR" to "线性（匀速）",
                "EASE_OUT" to "先快后慢（ease-out）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "animation.frame_log",

            retired = true,
            group = SettingGroups.ANIMATION,

            subGroup = "质量与帧率",
            kind = SettingKind.SWITCH,
            title = "每秒帧率日志",
            summary = "每秒在日志里打印一次实得帧率与实际 ms（动画帧 + 身后窗口采集两条节拍），用来核对设置里的帧率/延迟是否真的达成。默认关 —— 常开会以约 2 行/秒的速度挤占日志轮转窗口。仅诊断用，不影响画面与动画。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(

            key = "animation.style",

            group = SettingGroups.ANIMATION,
            subGroup = "动效风格",
            kind = SettingKind.LIST,
            title = "动效风格（总开关）",
            summary = "选具体风格 = **覆盖全部组件**（忽略各组件自己的设置）；选「各组件各自设置」= 总开关不干预，" +
                "由「歌词动效风格」「播放控件动效风格」分别决定。",
            default = AnimationStyleResolver.TOTAL_PER_COMPONENT,
            options = listOf(
                AnimationStyleResolver.TOTAL_PER_COMPONENT to "各组件各自设置（总开关不干预）",
                "FADE" to "淡入（覆盖全部组件）",
                "SLIDE" to "滑入（覆盖全部组件）",
                "POP" to "弹出（覆盖全部组件）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "settings.auto_ungate_on_touch",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "行为",
            kind = SettingKind.SWITCH,
            title = "调整颜色时自动打开被关掉的功能",
            default = true,
            phase = SettingPhase.P1,

            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "island.border.enabled",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SWITCH,
            title = "显示边框",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.border.width_tenth_px",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框宽度（0.1 像素）",
            summary = "仅在开启「显示边框」时生效。10 = 1.0 像素。",

            default = 10,
            range = 0.0..80.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.LIST,
            title = "边框样式",
            summary = "硬边：画一条实线边框。",

            default = "SOLID",
            options = listOf(
                "SOLID" to "硬边（实线边框）",

                "EDGE_DISSOLVE" to "溶边（最外一圈整体淡化，无实线；需背景不透明）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "island.border.dissolve_band_percent",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "溶边渐变范围（岛体高度的 %）",
            summary = "仅在边框样式选「溶边」时生效。50 = 渐变带从边缘一直铺到岛体中线" +
                "（最平滑）；值越小边缘越硬，0 = 完全不渐变。",

            default = 50,
            range = 0.0..50.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color_mode",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.LIST,
            title = "边框颜色来源",
            summary = "仅在开启「显示边框」时生效。",

            default = "manual",
            options = listOf("manual" to "手动指定", "cover" to "跟随封面取色"),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "island.border.color.r",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 R",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color.g",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 G",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.border.color.b",
            group = SettingGroups.ISLAND_APPEARANCE,
            subGroup = "边框",
            kind = SettingKind.SEEKBAR,
            title = "边框色 B",
            summary = "仅在开启「显示边框」时生效。",
            default = 255,
            range = 0.0..255.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.component",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "右端插槽内容",
            summary = "词岛右端那块固定宽的区域里放什么。「关闭」= 留空（歌词区不再让位）；" +

                "「小频谱」= 5 根细条（颜色可随音量流光）；「放射状频谱」= 从中心向外辐射的条"
                + "（★ 本档下插槽会变宽：宽 = 词岛高）；「时钟」= 当前时间" +
                "（字形由「时钟字形」另选：七段 / 点阵 / 像素风）。",
            default = "NONE",
            options = listOf(
                "NONE" to "关闭",
                "SPECTRUM" to "小频谱（5 根细条）",

"RADIAL" to "放射状频谱",
"RING" to "环状频谱",
"CLOCK" to "时钟",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.spectrum_bars",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "小频谱条数",
            summary = "右端插槽放「小频谱」时画几根条。仅在该档生效" +
                "（「放射状 / 环状频谱」的条数由几何自动推导，不受本项影响）。",
            default = "5",
            options = listOf(
                "4" to "4 根",
                "5" to "5 根（默认）",
                "6" to "6 根",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.spectrum_color_mode",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "插槽频谱颜色来源",
            summary = "右端插槽的频谱用哪个颜色来源。默认「跟随主频谱」——" +
                "改成别的档位后，插槽与主频谱**互不影响**。",
            default = "follow",
            options = listOf(
                "follow" to "跟随主频谱（默认）",
                "manual" to "手动指定",
                "cover" to "跟随封面取色",
                "level" to "随音量变化",
                "cover_flow" to "封面多色流光",
                "cover_camo" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.spectrum_color",

            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.COLOR,
            title = "插槽频谱颜色",
            summary = "插槽频谱颜色（插槽频谱颜色来源选「手动指定」时生效）",
            default = 0xFF84D8BC.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,
        ),
        SettingDescriptor(

            key = "island.right_slot.radial_inner_percent",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.SEEKBAR,
            title = "放射·内半径（%）",
            summary = "「放射状频谱」条形从多远处开始（占外半径的百分比）。" +
                "0 = 交于圆心；调大后中心会空出一块（变成环形观感）。",
            default = 0,
            range = 0.0..85.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.ring_inner_percent",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.SEEKBAR,
            title = "环状·内半径（%）",
            summary = "「环状频谱」中心空洞的大小（占外半径的百分比）。" +
                "调小 = 环变厚、中心洞变小。",
            default = 55,
            range = 0.0..85.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.polar_outer_percent",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.SEEKBAR,
            title = "极坐标·外半径（%）",
            summary = "「放射状 / 环状频谱」条形能伸到多远（占可用半径的百分比）。" +
                "100 = 撑满插槽；调小 = 整体缩小。",
            default = 100,
            range = 10.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "island.right_slot.polar_style",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "极坐标显示形态",
            summary = "「放射状 / 环状频谱」画成柱状还是线状。线状 = 一圈连续折线" +
                "（以中半径为基线、内外各起伏一半）；线宽会随柱距自动保持可读，" +
                "不会细到看不见。",
            default = "BARS",

            options = listOf(
                "BARS" to "柱状（默认）",
                "LINE" to "线状",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "clock.hour_format",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "时钟时制",
            summary = "右端插槽放「时钟」时，时间用 24 小时制还是 12 小时制。" +
                "12 小时制不补零（如「9:05」）。",
            default = "H24",
            options = listOf(
                "H24" to "24 小时制",
                "H12" to "12 小时制",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "clock.style",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.LIST,
            title = "时钟字形",
            summary = "右端插槽放「时钟」时，时间用什么字形画。" +
                "「七段数码管」= 经典数字钟；「点阵」= LED 点阵屏（点之间有缝）；" +
                "「像素风」= 方块位图字（方块连成笔画）。",
            default = "SEGMENT",
            options = listOf(
                "SEGMENT" to "七段数码管",
                "DOT" to "点阵",
                "PIXEL" to "像素风",
            ),
            phase = SettingPhase.P1,

            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "spectrum.color_mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.LIST,
            title = "频谱颜色来源",
            default = "manual",
            options = listOf(
                "manual" to "手动指定",
                "cover" to "跟随封面取色",

                "level" to "随音量流光",

                "cover_flow" to "封面多色流光",

                "cover_camo" to "封面迷彩碎色流光",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),

        SettingDescriptor(
            key = "spectrum.color",

            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.COLOR,
            title = "频谱颜色",

            default = 0xFF84D8BC.toInt(),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
            alphaAllowed = false,

            summary = "频谱颜色（频谱颜色来源选「手动指定」时生效）",
        ),
        SettingDescriptor(
            key = "spectrum.anchor",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.LIST,
            title = "频谱贴边",

            default = "BOTTOM",
            options = listOf(
                "BOTTOM" to "贴底边",
                "TOP" to "贴顶边",

                "RIGHT" to "词岛右端（5 根细条）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(

            key = "spectrum.secondary.mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.LIST,

            title = "副频谱位置",

            default = "OFF",
            summary = "受「显示频谱」总开关管理。副频谱画在词岛底边/顶边，透明度单独可调。",
            options = listOf(
                "OFF" to "关闭（默认）",
                "BOTTOM" to "底边",
                "TOP" to "顶边",
                "BOTH" to "底边 + 顶边",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "spectrum.secondary.opacity_percent",
            group = SettingGroups.SPECTRUM,
            subGroup = "开关与位置",
            kind = SettingKind.SEEKBAR,
            title = "副频谱不透明度 %",

            default = 35,
            range = 0.0..100.0,
            summary = "仅在副频谱不为「关闭」时生效。建议明显低于「频谱不透明度」。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.bar_pitch_mode",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.LIST,
            title = "频谱条宽度",

            default = "AUTO",
            summary = "自动：窄词岛时把频谱条做细以塞下更多条（宽词岛观感不变）。固定：用下面「频谱条间距」的值。",
            options = listOf(
                "AUTO" to "自动（窄岛变细）",
                "FIXED" to "固定（用下方间距值）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(

            key = "spectrum.bar_pitch_tenth_px",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱条间距（0.1 像素）",

            default = 70,

            range = 0.0..200.0,
            summary = "仅在「频谱条宽度」选「固定」时生效。这是柱间距（条 + 缝），不是条宽本身。" +
                "拖到 0 时缝宽为 0、相邻条无缝 ⇒ 呈现实心频谱。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "island.right_slot.spectrum_bar_pitch_tenth_px",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.SEEKBAR,
            title = "插槽极坐标间距（0.1 像素）",

            default = 70,
            range = 0.0..200.0,

            step = 1,

            summary = "影响「放射 / 环形」两档的条数与间隙（条数 = 周长 ÷ 本值）。" +
                "「侧边条」档不受它影响：五根粗细由槽宽 ÷ 条数均分，本键只决定「是否实心」。" +
                "拖到 0 时放射 / 环形按实心绘制。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.secondary.bar_pitch_tenth_px",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "副频谱条间距（0.1 像素）",
            default = 70,
            range = 0.0..200.0,
            step = 10,
            summary = "只影响副频谱（第二行）。主频谱用「频谱条间距」那一项。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "island.right_slot.spectrum_opacity_pct",
            group = SettingGroups.RIGHT_SLOT,
            subGroup = "全部",
            kind = SettingKind.SEEKBAR,
            title = "插槽频谱透明度（%）",

            default = 100,
            range = 0.0..100.0,
            step = 5,

            summary = "对「侧边条 / 放射 / 环形」三档同时生效。100% = 不透明；0% = 完全看不见。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "spectrum.bar_shape",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.LIST,
            title = "频谱条形状",
            summary = "圆角：柱体两端为圆头（改动前的观感）。方角：柱体四角为直角。" +
                "间距拖到 0（实心频谱）时恒为方角 —— 圆角会在相邻柱之间留出腰形缺口。",

            default = SPECTRUM_BAR_SHAPE_ROUND,
            options = listOf(
                SPECTRUM_BAR_SHAPE_ROUND to "圆角",
                SPECTRUM_BAR_SHAPE_SQUARE to "方角",
            ),
            phase = SettingPhase.P1,

            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(

            key = "spectrum.height_percent",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱高度（占歌词区百分比）",
            summary = "100% = 占满歌词区。开启「上下对称双峰」时，双峰以歌词行/两行的中线为对称轴，上下各占该百分比的一半。",

            default = 100,
            range = 10.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.bounce_pct",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SEEKBAR,
            title = "频谱跳动幅度（%）",

            default = 100,
            range = 100.0..300.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.progress_tint",
            group = SettingGroups.SPECTRUM,
            subGroup = "进度色差",
            kind = SettingKind.SWITCH,
            title = "用频谱色差表示播放进度",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "spectrum.fade_top",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.SWITCH,
            title = "顶端淡出",

            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.opacity_pct",
            group = SettingGroups.SPECTRUM,
            subGroup = "外观",
            kind = SettingKind.SEEKBAR,
            title = "频谱不透明度 %",

            default = 75,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.symmetric",
            group = SettingGroups.SPECTRUM,
            subGroup = "条与高度",
            kind = SettingKind.SWITCH,
            title = "上下对称双峰",

            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "spectrum.progress_tint_alpha",
            group = SettingGroups.SPECTRUM,
            subGroup = "进度色差",
            kind = SettingKind.SEEKBAR,

            title = "进度色差：未播放段的透明度（%　0=无区别 / 100=只显示已播放）",

            default = 100,
            range = 0.0..100.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,

        ),
    )

    private fun groupH(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "controls.triggerMode",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.LIST,
            title = "控件触发方式",

            default = "GLYPH_HIT",
            options = listOf(
                "GLYPH_HIT" to "字形命中",
                "ISLAND_HIT" to "轮廓命中（悬停到词岛可见区域即展开）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.enterDelayMs",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.SEEKBAR,
            title = "悬停进入延迟",
            default = 120,
            range = 0.0..2000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.exitDelayMs",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.SEEKBAR,
            title = "悬停离开延迟",
            default = 250,
            range = 0.0..3000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.hitPaddingPx",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.SEEKBAR,
            title = "命中框外扩",
            default = 2,
            range = 0.0..16.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.position",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.LIST,
            title = "控件位置",

            default = "BOTTOM",
            options = listOf("TOP" to "上方", "BOTTOM" to "下方"),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.gapPx",
            group = SettingGroups.CONTROLS,
            subGroup = "布局",
            kind = SettingKind.SEEKBAR,
            title = "控件与歌词间距",
            default = 8,
            range = 0.0..48.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.showProgress",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.SWITCH,
            title = "显示进度条",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "controls.autoHideMs",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.SEEKBAR,
            title = "控件自动隐藏延迟",

            default = 0,
            range = 0.0..30000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "controls.animation_style",
            group = SettingGroups.CONTROLS,
            subGroup = "触发",
            kind = SettingKind.LIST,
            title = "播放控件动效风格",
            summary = "控件条出现的方式。默认「跟随总开关」；选具体风格可让控件条单独一套。",
            default = AnimationStyleResolver.FOLLOW_TOTAL,
            options = AnimationStyleResolver.componentOptions(),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "controls.progress_style",
            group = SettingGroups.CONTROLS,
            subGroup = "进度与隐藏",
            kind = SettingKind.LIST,
            title = "进度条形态",
            summary = "经典 = 实心条 + 常显圆点（原样）；" +
                "流光 = 已播放段用羽化光带推进，圆点仅在悬停或拖动时出现。",
            default = PROGRESS_STYLE_CLASSIC,
            options = listOf(
                PROGRESS_STYLE_CLASSIC to "经典（实心 + 常显圆点）",
                PROGRESS_STYLE_GLOW to "流光（羽化推进 + 圆点不常驻）",
            ),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
    )

    private fun groupI(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "layout.noLyrics.mode",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.LIST,
            title = "无歌词时的显示",

            default = "PLACEHOLDER",
            options = listOf(
                "HIDE_LINE" to "隐藏歌词行",
                "PLACEHOLDER" to "显示占位文本",
                "HIDE_ISLAND" to "隐藏整个词岛",
            ),
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.noLyrics.showTrackTitle",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时显示曲名与作者",
            default = true,
            phase = SettingPhase.P0,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(

            key = "layout.noLyrics.showAlbum",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时同时显示专辑",

            default = false,

            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "layout.noLyrics.hideWhenInstrumental",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时隐藏歌词占位句",

            summary = "只隐藏占位句（如「纯音乐，请欣赏」「暂无歌词」）；**曲名与作者仍然显示**。窗口、封面与频谱不受影响。",
            default = false,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
        SettingDescriptor(
            key = "layout.noLyrics.titleInTranslation",
            group = SettingGroups.LYRIC,
            subGroup = "无歌词",
            kind = SettingKind.SWITCH,
            title = "无歌词时在译文行显示曲名",

            default = false,
            summary = "仅在当前曲目没有歌词时生效。开启后曲名会额外画在第二行（译文行的位置）。",
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "main_lyric.letter_spacing_tenth_px",
            group = SettingGroups.LYRIC,
            subGroup = "排版",
            kind = SettingKind.SEEKBAR,
            title = "主歌词字间距（0.1 像素）",
            summary = "同一行内字符之间的距离。0 = 不加（默认）；10 = 1.0 像素；上限 200 = 20.0 像素。字号越大，同样的像素字距看起来越紧。",
            default = 0,
            range = 0.0..200.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "translation.letter_spacing.follow_main",
            group = SettingGroups.LYRIC,
            subGroup = "排版",
            kind = SettingKind.SWITCH,
            title = "副歌词字间距跟随主歌词",
            summary = "开启时副歌词按主歌词的字距按字号比例缩放（字越大间距越大）；关闭时副歌词不加字距。",
            default = true,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),
    )

    private fun groupJ(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "lyric.offset_ms",
            group = SettingGroups.LYRIC,
            subGroup = "高亮",
            kind = SettingKind.SEEKBAR,
            title = "歌词高亮偏移 ms",
            default = 0,

            range = -2000.0..2000.0,
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

        SettingDescriptor(
            key = "lyric.animation_style",
            group = SettingGroups.LYRIC,
            subGroup = "高亮",
            kind = SettingKind.LIST,
            title = "歌词动效风格",
            summary = "歌词换行时**旧行**的退场方式（新行不受影响）。默认「跟随总开关」；" +
                "选具体风格可让歌词单独一套，而总开关保持不动。",
            default = AnimationStyleResolver.FOLLOW_TOTAL,
            options = AnimationStyleResolver.componentOptions(),
            phase = SettingPhase.P1,
            preview = PreviewKind.THROTTLED,
        ),

    )

    private fun groupK(): List<SettingDescriptor> = listOf(
        SettingDescriptor(
            key = "menu.submenu_open_delay_ms",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单延迟",
            kind = SettingKind.SEEKBAR,
            title = "子菜单展开延迟（ms）",

            default = 180,
            range = 0.0..1000.0,

            step = 10,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "menu.submenu_close_delay_ms",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单延迟",
            kind = SettingKind.SEEKBAR,

            title = "菜单收起延迟（ms）",
            summary = "鼠标离开菜单后多久收起。**一级菜单与子菜单共用这一条**" +
                "（子菜单用你设的值；一级菜单另有 800 ms 下限，避免手一移开就消失）。",

            default = 250,
            range = 0.0..1000.0,

            step = 10,
            phase = SettingPhase.P1,
            preview = PreviewKind.IMMEDIATE,
        ),
        SettingDescriptor(
            key = "menu.opacity_pct",
            group = SettingGroups.MENU_SETTINGS,
            subGroup = "菜单外观",
            kind = SettingKind.SEEKBAR,
            title = "右键菜单不透明度（%）",

            default = 100,
            range = 30.0..100.0,
            phase = SettingPhase.P1,

            preview = PreviewKind.IMMEDIATE,
        ),
    )

    private val declared: List<SettingDescriptor> = buildList {
        addAll(groupA())
        addAll(groupB())
        addAll(groupC())
        addAll(groupD())
        addAll(groupE())
        addAll(groupF())
        addAll(groupG())
        addAll(groupH())
        addAll(groupI())
        addAll(groupJ())
        addAll(groupK())
    }

    val all: List<SettingDescriptor> = declared.sortedBy { d ->
        SettingGroups.ALL.indexOf(d.group) * 1000 + d.subOrder()
    }

    private fun SettingDescriptor.subOrder(): Int {
        val subs = SettingGroups.SUBS[group].orEmpty()
        val at = subs.indexOf(subGroup)
        return if (at >= 0) at else subs.size
    }

    val byGroup: Map<String, List<SettingDescriptor>> = all.groupBy { it.group }

    val byComponentThenSub: Map<String, Map<String, List<SettingDescriptor>>> =
        SettingGroups.ALL.associateWith { component ->
            val subs = SettingGroups.SUBS.getValue(component)
            subs.associateWith { sub ->
                all.filter { it.group == component && it.subGroup == sub }
            }
        }

    val byKey: Map<String, SettingDescriptor> = all.associateBy { it.key }

    val byPhase: Map<SettingPhase, List<SettingDescriptor>> = all.groupBy { it.phase }

    fun descriptor(key: String): SettingDescriptor? = byKey[key]

    init {
        verify()
    }

    private fun verify() {
        check(all.size == EXPECTED_TOTAL) {
            "注册表项数应为 $EXPECTED_TOTAL，实为 ${all.size}（首版设置总表 §2）"
        }

        val duplicated = all.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(duplicated.isEmpty()) { "键名重复：$duplicated" }

        all.forEach { descriptor ->
            val key = descriptor.key
            check('.' in key) { "键名应为点号嵌套：$key" }
            check(!key.startsWith('.') && !key.endsWith('.')) { "键名首尾不应有句点：$key" }
            check(key.none { it.isWhitespace() }) { "键名不应含空白：$key" }
        }

        val expectedByKind = mapOf(

            SettingKind.SWITCH to 41,

            SettingKind.SEEKBAR to 75,

            SettingKind.LIST to 39,
            SettingKind.EDITTEXT to 1,

            SettingKind.COLOR to 4,
            SettingKind.FONT to 1,
        )
        expectedByKind.forEach { (kind, expected) ->
            val actual = all.count { it.kind == kind }
            check(actual == expected) {
                "类型 ${kind.name} 应有 $expected 项，实为 $actual（首版设置总表 §2.2）"
            }
        }

        val expectedByPhase = mapOf(
            SettingPhase.P0 to 5,

            SettingPhase.P1 to 156,
            SettingPhase.P4 to 0,
        )
        expectedByPhase.forEach { (phase, expected) ->
            val actual = all.count { it.phase == phase }
            check(actual == expected) {
                "阶段 ${phase.name} 应有 $expected 项，实为 $actual（首版设置总表 §2.1）"
            }
        }

        val expectedByGroup = mapOf(

            SettingGroups.LYRIC to 20,

            SettingGroups.LYRIC_COLOR_OUTLINE to 28,

            SettingGroups.ISLAND to 17,

            SettingGroups.ISLAND_APPEARANCE to 36,

            SettingGroups.RIGHT_SLOT to 12,

            SettingGroups.SPECTRUM to 17,

            SettingGroups.COVER to 8,

            SettingGroups.CONTROLS to 10,

            SettingGroups.ANIMATION to 9,

            SettingGroups.MENU_SETTINGS to 4,
        )
        check(expectedByGroup.size == SettingGroups.ALL.size) {
            "期望分组数与 SettingGroups.ALL 不一致：${expectedByGroup.size} vs ${SettingGroups.ALL.size}"
        }
        check(expectedByGroup.values.sum() == EXPECTED_TOTAL) {
            "一级组的项数之和应为 $EXPECTED_TOTAL，实为 ${expectedByGroup.values.sum()}"
        }
        expectedByGroup.forEach { (group, expected) ->
            val actual = byGroup[group]?.size ?: 0
            check(actual == expected) {
                "组件「$group」应有 $expected 项，实为 $actual"
            }
        }

        val stray = byGroup.keys - expectedByGroup.keys
        check(stray.isEmpty()) { "存在未在 SettingGroups.ALL 声明的分组：$stray" }

        check(SettingGroups.SUBS.keys == SettingGroups.ALL.toSet()) {
            "SettingGroups.SUBS 的组件集合与 ALL 不一致：" +
                "SUBS=${SettingGroups.SUBS.keys}，ALL=${SettingGroups.ALL}"
        }
        val undeclared = all.filter { it.subGroup !in SettingGroups.SUBS[it.group].orEmpty() }
        check(undeclared.isEmpty()) {
            "以下设置项的 subGroup 未在其组件下声明：${
                undeclared.map { "${it.key}(group=${it.group}, subGroup=${it.subGroup})" }
            }"
        }
        val emptySubs = SettingGroups.ALL.flatMap { component ->
            SettingGroups.SUBS.getValue(component).filter { sub ->
                all.none { it.group == component && it.subGroup == sub }
            }.map { "$component · $it" }
        }
        check(emptySubs.isEmpty()) { "以下细类声明了但没有任何设置项（空壳分类）：$emptySubs" }

        val alphaAllowedColors = all.filter { it.kind == SettingKind.COLOR && it.alphaAllowed }
        check(alphaAllowedColors.isEmpty()) {
            "颜色项均须 alphaAllowed=false，违规：${alphaAllowedColors.map { it.key }}"
        }

        val expectedP0Keys = setOf(
            "island.enabled",
            "layout.translation.enabled",
            "window.position",
            "layout.noLyrics.mode",
            "layout.noLyrics.showTrackTitle",
        )
        val actualP0Keys = byPhase[SettingPhase.P0].orEmpty().map { it.key }.toSet()
        check(actualP0Keys == expectedP0Keys) {
            "P0 键名与总表 §2.1 不符。缺：${expectedP0Keys - actualP0Keys}；多：${actualP0Keys - expectedP0Keys}"
        }
    }

const val EXPECTED_TOTAL = 161

    data class ColorGroup(val prefix: String, val title: String, val keys: List<String>)

    val COLOR_GROUPS: List<ColorGroup> = listOf(
        ColorGroup(
            "main_lyric.fill.color", "已唱歌词颜色",
            listOf(
                "main_lyric.fill.color.r", "main_lyric.fill.color.g",
                "main_lyric.fill.color.b", "main_lyric.fill.color.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.fill.unsung", "未唱歌词颜色",
            listOf(
                "main_lyric.fill.unsung.r", "main_lyric.fill.unsung.g",
                "main_lyric.fill.unsung.b", "main_lyric.fill.unsung.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.translation.color", "译文歌词颜色",
            listOf(
                "main_lyric.translation.color.r", "main_lyric.translation.color.g",
                "main_lyric.translation.color.b", "main_lyric.translation.color.lightness",
            ),
        ),
        ColorGroup(
            "main_lyric.outline.color", "主歌词描边色",
            listOf(
                "main_lyric.outline.color.r", "main_lyric.outline.color.g",
                "main_lyric.outline.color.b",
            ),
        ),
        ColorGroup(
            "translation.outline.color", "副歌词描边色",
            listOf(
                "translation.outline.color.r", "translation.outline.color.g",
                "translation.outline.color.b",
            ),
        ),
        ColorGroup(
            "island.border.color", "边框色",
            listOf(
                "island.border.color.r", "island.border.color.g", "island.border.color.b",
            ),
        ),

        ColorGroup("spectrum.color", "频谱颜色", listOf("spectrum.color")),

        ColorGroup("background.color", "背景颜色", listOf("background.color")),

        ColorGroup("material.fill.color_end", "渐变止色", listOf("material.fill.color_end")),

        ColorGroup(
            "island.right_slot.spectrum_color",
            "插槽频谱颜色",
            listOf("island.right_slot.spectrum_color"),
        ),
    )

    fun colorGroupFor(key: String): ColorGroup? = COLOR_GROUPS.firstOrNull { key in it.keys }

    val SELF_DRAWN_KEYS: Set<String> = setOf(
        "main_lyric.font.recent",
        "window.position",

        "material.fill.color_end",

        "main_lyric.fill.color.r",
        "main_lyric.fill.color.g",
        "main_lyric.fill.color.b",
        "main_lyric.fill.color.lightness",
        "main_lyric.fill.unsung.r",
        "main_lyric.fill.unsung.g",
        "main_lyric.fill.unsung.b",
        "main_lyric.fill.unsung.lightness",
        "main_lyric.translation.color.r",
        "main_lyric.translation.color.g",
        "main_lyric.translation.color.b",
        "main_lyric.translation.color.lightness",

        "main_lyric.outline.color.r",
        "main_lyric.outline.color.g",
        "main_lyric.outline.color.b",
        "translation.outline.color.r",
        "translation.outline.color.g",
        "translation.outline.color.b",
        "island.border.color.r",
        "island.border.color.g",
        "island.border.color.b",

        "spectrum.color",

        "background.color",

        "island.right_slot.spectrum_color",
    )

    init {

        val unknown = SELF_DRAWN_KEYS - all.map { it.key }.toSet()
        check(unknown.isEmpty()) { "SELF_DRAWN_KEYS 含未定义的键：$unknown" }
    }

    val spwPageDescriptors: List<SettingDescriptor>
        get() = declared
            .filterNot { it.key in SELF_DRAWN_KEYS }

            .filterNot { it.retired }

            .sortedBy { d -> SettingGroups.ALL.indexOf(d.group) * 1000 + d.subOrder() }

    val selfDrawnDescriptors: List<SettingDescriptor>
        get() = all.filter { it.key in SELF_DRAWN_KEYS }
}

const val SPECTRUM_BAR_SHAPE_ROUND = "ROUND"

const val SPECTRUM_BAR_SHAPE_SQUARE = "SQUARE"

const val PROGRESS_STYLE_CLASSIC = "CLASSIC"

const val PROGRESS_STYLE_GLOW = "GLOW"

fun spectrumBarCornerRatio(shape: String?): Float =
    if (shape == SPECTRUM_BAR_SHAPE_SQUARE) 0f else 1f

public const val KARAOKE_MODE_PER_CHAR: String = "PER_CHAR"
public const val KARAOKE_MODE_SMOOTH: String = "SMOOTH"
public const val KARAOKE_MODE_LINE: String = "LINE"

public const val BORDER_MODE_SOLID: String = "SOLID"

public const val BORDER_MODE_FADE_OUT: String = "FADE_OUT"

public const val BORDER_MODE_EDGE_DISSOLVE: String = "EDGE_DISSOLVE"

fun borderFadeLayers(strokeWidthPx: Float): List<BorderFadeLayer> {
    if (!(strokeWidthPx > 0f)) return emptyList()
    val layers = 5
    val band = strokeWidthPx * 3.5f
    return (0 until layers).map { i ->
        val t = i / (layers - 1f)
        BorderFadeLayer(
            insetPx = band * t,
            alpha = ((1f - t) * (1f - t) * 255f).toInt().coerceIn(0, 255),
            widthPx = strokeWidthPx * (1f - t * 0.6f),
        )
    }
}

public const val MAX_DISSOLVE_BAND_PERCENT: Int = 50

fun dissolveBandPx(islandHeightPx: Float, bandPercent: Int): Float {
    if (!islandHeightPx.isFinite() || islandHeightPx <= 0f) return 0f
    val pct = bandPercent.coerceIn(0, MAX_DISSOLVE_BAND_PERCENT)
    if (pct <= 0) return 0f

    return (islandHeightPx * pct / 100f + 0.5f).toInt().coerceAtLeast(1).toFloat()
}

data class BorderFadeLayer(
    val insetPx: Float,
    val alpha: Int,
    val widthPx: Float,
)
