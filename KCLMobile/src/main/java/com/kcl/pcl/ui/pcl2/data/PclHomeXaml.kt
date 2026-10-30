/*
 * KCL Mobile — PCL2 UI port
 *
 * PCL 主页文件（XAML）→ HTML 转换器。
 *
 * PCL2 的自定义主页与主页预设都是 WPF XAML，PCL 用自己的 XAML 解析器渲染。
 * Android 端没有 WPF，这里用 XmlPullParser 把常见元素转成带内联样式的 HTML，
 * 交给主页的 WebView 显示。相比逐元素硬编码，这里额外做了一件关键的事：
 *
 *   **解析 `<Style x:Key="...">` 定义表** —— PCL 主页把「蓝色版本标签」「图片边框」
 *   「标题字号/颜色」这些都写在 `<StackPanel.Resources>` 里，元素只用
 *   `Style="{StaticResource Xxx}"` 引用。把样式表先收集起来再合并到元素属性上，
 *   才能还原出 PCL 里的真实排版（否则胶囊标签、图片边框全丢）。
 *
 * 还要跳过几类**只做定义、不产生可见内容**的元素，其中最容易踩坑的是
 * `<sys:String x:Key="CloudIcon">M480 224c43 0 84.7...</sys:String>` ——
 * PCL 把图标的 SVG 路径存成字符串资源，如果不跳过，那串坐标会被当正文刷屏。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object PclHomeXaml {

    /** PCL 默认主题（龙猫蓝）的静态色值 —— 与 PCL 截图里的配色一致。 */
    private const val C1 = "#404040" // ColorBrush1 主文字
    private const val C2 = "#0B5BCB" // ColorBrush2
    private const val C3 = "#1370F3" // ColorBrush3 主色
    private const val C4 = "#4890F5" // ColorBrush4
    private const val C5 = "#96C0F9" // ColorBrush5
    private const val C6 = "#D5E6FD" // ColorBrush6
    private const val C7 = "#E0EAFD" // ColorBrush7
    private const val C8 = "#EAF2FE" // ColorBrush8
    private const val GRAY2 = "#737373"
    private const val GRAY3 = "#8C8C8C"
    private const val GRAY4 = "#A6A6A6"
    private const val RED = "#CE2111"

    /** 只做定义、不产生可见内容的元素（整棵子树跳过）。 */
    private val DEFINITION_ELEMENTS = setOf(
        // sys:String —— 图标 SVG 路径资源（刷屏的元凶）
        "string",
        // 模板 / 触发器 / 行列定义
        "controltemplate", "datatemplate", "template", "triggers",
        "stackpanel.triggers", "style.triggers", "controltemplate.triggers", "mycard.triggers",
        "columndefinitions", "rowdefinitions",
        "grid.columndefinitions", "grid.rowdefinitions",
    )
    // 注意：`<StackPanel.Resources>` / `<Grid.Resources>` 这类资源字典**不能**整块跳过 ——
    // 它们里面既有要跳过的 `<sys:String>`（图标坐标），也有要收集的 `<Style>`（胶囊标签等）。
    // 资源字典按普通空容器处理，里面的元素各自走自己的分支即可。

    /** 矢量图形 / 渐变画刷：只做装饰，属性里的坐标不能当文本。 */
    private val GRAPHIC_ELEMENTS = setOf(
        "path", "pathgeometry", "pathfigure", "line", "polygon", "polyline",
        "ellipse", "lineargradientbrush", "radialgradientbrush",
        "gradientstop", "discretegradientstop", "solidcolorbrush", "geometrygroup",
    )

    /**
     * 把 PCL 主页 XAML 转成完整 HTML。
     *
     * @param xaml PCL 主页 XAML 原文（`{hint}` / `{cave}` 等占位符应先替换掉）
     */
    fun convertToHtml(xaml: String): String {
        // 先扫一遍所有渐变色标：`<Rectangle>` 的填充常写成子元素
        // `<Rectangle.Fill><LinearGradientBrush><GradientStop Color=.../>`，
        // 流式解析到 Rectangle 时还看不到这些颜色，所以提前取出来按序配给矩形。
        val gradientColors = extractGradientColors(xaml)
        val body = try {
            parseToBody(xaml, gradientColors)
        } catch (_: Exception) {
            // 第一次失败多半是裸 `&`（写 URL 没转义成 &amp;）这类非法 XML，清理后重试。
            try {
                parseToBody(sanitizeXml(xaml), gradientColors)
            } catch (_: Exception) {
                null
            }
        }
        // 彻底解析不了也绝不把源码刷出来，给一张说明卡片。
        return wrapPage(body?.takeIf { it.isNotBlank() } ?: errorCard)
    }

    /** 提取 XAML 里所有 `GradientStop` 的颜色（按出现顺序）。 */
    private fun extractGradientColors(xaml: String): List<String> =
        Regex("GradientStop\\b[^>]*Color=\"(#[0-9A-Fa-f]{6,8})\"", RegexOption.IGNORE_CASE)
            .findAll(xaml)
            .map { it.groupValues[1] }
            .toList()

    // ---------------------------------------------------------------- 解析

    private class OpenTag(val html: String, val close: String)

    private fun parseToBody(xaml: String, gradientColors: List<String>): String {
        val body = StringBuilder()
        val stack = ArrayDeque<OpenTag>()
        /** Style 定义表：x:Key → (属性名 → 值)。 */
        val styleTable = HashMap<String, Map<String, String>>()

        var collectingKey: String? = null
        var collectingSetters: HashMap<String, String>? = null
        /** 跳过深度：>= 0 表示正处在「定义元素」子树内，期间不输出任何内容。 */
        var skipDepth = -1
        /** 第几个 Rectangle（用来按顺序领渐变颜色）。 */
        var rectIndex = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xaml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().substringAfterLast(':').lowercase()
                    val attrs = buildAttrs(parser)

                    if (skipDepth >= 0) {
                        stack.addLast(OpenTag("", ""))
                    } else if (name == "style") {
                        collectingKey = attrs["x:key"] ?: attrs["key"]
                        collectingSetters = HashMap()
                        stack.addLast(OpenTag("", ""))
                    } else if (name == "setter" && collectingSetters != null) {
                        val prop = attrs["property"]?.lowercase()
                        val value = attrs["value"].orEmpty()
                        if (prop != null && value.isNotBlank()) collectingSetters!![prop] = value
                        stack.addLast(OpenTag("", ""))
                    } else if (name in DEFINITION_ELEMENTS) {
                        skipDepth = stack.size
                        stack.addLast(OpenTag("", ""))
                    } else if (name == "rectangle") {
                        // Rectangle 是横幅色块（渐变条），要保留；它常带 Panel.ZIndex，
                        // 所以必须**先**于下面的 ZIndex 判断处理。
                        val merged = mergeStyle(attrs, styleTable)
                        val open = rectangleTag(merged, gradientColors, rectIndex)
                        if (merged["fill"].isNullOrBlank()) rectIndex++
                        body.append(open.html)
                        stack.addLast(open)
                    } else if (attrs.containsKey("panel.zindex")) {
                        // 带 Panel.ZIndex 的其余元素是**叠在横幅上的装饰**（小图标、角标）。
                        // 流式 HTML 里没有真正的 Z 轴，它们只会把卡片撑出一大块空白，
                        // 所以整棵子树跳过。
                        skipDepth = stack.size
                        stack.addLast(OpenTag("", ""))
                    } else {
                        val merged = mergeStyle(attrs, styleTable)
                        val open = startElement(name, merged)
                        body.append(open.html)
                        stack.addLast(open)
                    }
                }

                XmlPullParser.TEXT -> {
                    if (skipDepth < 0 && collectingKey == null) {
                        val text = parser.text
                        // 过滤 SVG 路径坐标串（防 CDATA / 异常文本漏出来）
                        if (!text.isNullOrBlank() && !looksLikeSvgPath(text)) {
                            body.append(escape(cleanText(text)))
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty().substringAfterLast(':').lowercase()
                    val open = stack.removeLastOrNull()
                    when {
                        skipDepth >= 0 -> if (stack.size <= skipDepth) skipDepth = -1
                        name == "style" -> {
                            val key = collectingKey
                            val setters = collectingSetters
                            if (key != null && setters != null && setters.isNotEmpty()) {
                                styleTable[key] = setters
                            }
                            collectingKey = null
                            collectingSetters = null
                        }
                        else -> body.append(open?.close ?: "")
                    }
                }
            }
            event = parser.next()
        }
        return body.toString()
    }

    private fun buildAttrs(parser: XmlPullParser): Map<String, String> =
        (0 until parser.attributeCount).associate {
            (parser.getAttributeName(it) ?: "").lowercase() to (parser.getAttributeValue(it) ?: "")
        }

    /**
     * 合并 `Style="{StaticResource Xxx}"` 引用的样式属性：样式值作默认，元素自身属性优先。
     */
    private fun mergeStyle(
        attrs: Map<String, String>,
        table: Map<String, Map<String, String>>,
    ): Map<String, String> {
        val ref = attrs["style"].orEmpty()
        if (!ref.startsWith("{")) return attrs
        val key = Regex("StaticResource\\s+([^}]+)").find(ref)?.groupValues?.get(1)?.trim() ?: return attrs
        val style = table[key] ?: return attrs
        return (style - "style") + attrs
    }

    /** 修复 XAML 里最常见的非法 XML 字符：没转义的裸 `&`。 */
    private fun sanitizeXml(xaml: String): String =
        Regex("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)").replace(xaml, "&amp;")

    /**
     * 主页作者偶尔会把广告脚本当正文黏进描述里（sodamc 的整合包简介就混着
     * `(adsbygoogle = window.adsbygoogle || []).push({});`）。这类 JS 片段直接剔除。
     */
    private fun cleanText(text: String): String {
        if (!text.contains("adsbygoogle", ignoreCase = true)) return text
        return text.replace(Regex("\\(adsbygoogle[\\s\\S]*?;", RegexOption.IGNORE_CASE), "")
    }

    /** SVG 路径坐标串特征：M/m 开头、够长、几乎全是数字与路径命令。 */
    private fun looksLikeSvgPath(text: String): Boolean {
        val t = text.trim()
        if (t.length < 40) return false
        if (t[0] != 'M' && t[0] != 'm') return false
        val allowed = t.count { c ->
            c.isDigit() || c == ' ' || c == '.' || c == ',' || c == '-' || c == '+' ||
                    c in "MmZzLlHhVvCcSsQqTtAaEe"
        }
        return allowed.toFloat() / t.length > 0.85f
    }

    // ---------------------------------------------------------------- 元素映射

    /**
     * `<Rectangle>` 色块（横幅背景条）。
     *
     * 尺寸照 WPF 的 Width/Height（`Width="2000"` 这种超宽值改成 100%，避免横向溢出）；
     * 背景优先用 `Fill` 纯色，否则按出现顺序领预提取的两枚 GradientStop 颜色做渐变。
     */
    private fun rectangleTag(
        attrs: Map<String, String>,
        gradientColors: List<String>,
        index: Int,
    ): OpenTag {
        val s = StringBuilder()
        appendBox(attrs, s, "margin")
        val h = attrs["height"]?.toFloatOrNull()
        if (h != null && h in 1f..600f) s.append("height:").append(h).append("px;")
        val w = attrs["width"]?.toFloatOrNull()
        if (w != null && w in 1f..1000f) s.append("width:").append(w).append("px;")
        else s.append("width:100%;")

        val fill = attrs["fill"]?.let { mapColor(it) }
        if (fill != null) {
            s.append("background:").append(fill).append(';')
        } else {
            val c1 = gradientColors.getOrNull(index * 2)
            val c2 = gradientColors.getOrNull(index * 2 + 1)
            when {
                c1 != null && c2 != null -> s.append("background:linear-gradient(135deg,")
                    .append(c1).append(',').append(c2).append(");")
                c1 != null -> s.append("background:").append(c1).append(';')
            }
        }
        if (h == null || h <= 0f) s.append("height:100%;")
        s.append("border-radius:4px;")
        return OpenTag("<div class=\"rect\"" + styleAttr(s) + ">", "</div>")
    }

    private fun startElement(name: String, attrs: Map<String, String>): OpenTag {
        // 图形元素：只画装饰，属性里的坐标一律不输出。
        if (name in GRAPHIC_ELEMENTS) return OpenTag("", "")

        val style = StringBuilder()
        return when (name) {
            "mycard" -> {
                val title = attrs["title"].orEmpty().cleanResource()
                // 教程：`CanSwap=True` 表示可折叠，`IsSwapped=True` 表示默认折叠。
                val canSwap = attrs["canswap"].equals("true", ignoreCase = true)
                val swapped = attrs["isswapped"].equals("true", ignoreCase = true)
                appendBox(attrs, style, "margin")
                val html = StringBuilder("<div class=\"card")
                if (canSwap && swapped) html.append(" collapsed")
                html.append('"').append(styleAttr(style)).append('>')
                if (title.isNotBlank() || canSwap) {
                    html.append("<div class=\"card-head\"")
                    // 点标题栏切换折叠状态（WebView 里跑一段最简 JS，不依赖任何库）。
                    if (canSwap) {
                        html.append(" onclick=\"this.parentNode.classList.toggle('collapsed')\"")
                    }
                    html.append("><span class=\"card-title\">")
                        .append(textHtml(title))
                        .append("</span>")
                    if (canSwap) html.append("<span class=\"card-arrow\">⌄</span>")
                    html.append("</div>")
                }
                html.append("<div class=\"card-body\">")
                OpenTag(html.toString(), "</div></div>")
            }

            "border" -> OpenTag("<div" + styleAttr(borderStyle(attrs)) + ">", "</div>")

            "contentcontrol" -> {
                // PCL 的「历史版本」分隔线：ContentControl + Separator 模板（两段线夹文字）。
                val content = attrs["content"].orEmpty().cleanResource()
                if (content.isNotBlank()) {
                    OpenTag("<div class=\"sep\"><span>${escape(content)}</span></div>", "")
                } else {
                    OpenTag("<div class=\"col\">", "</div>")
                }
            }

            "textblock" -> textBlock(attrs, style)

            // FlowDocument 行内元素：必须内联，不能当块级段落（否则一句话被拆成好几行）。
            "textbox", "run", "label", "inline" -> {
                val text = attrs["text"].orEmpty().cleanResource()
                val isCode = attrs["style"].orEmpty().contains("InlineCode", ignoreCase = true)
                val open = if (isCode) "<code>" else "<span>"
                val close = if (isCode) "</code>" else "</span>"
                val html = StringBuilder(open)
                if (text.isNotBlank()) html.append(escape(text))
                OpenTag(html.toString(), close)
            }

            "paragraph" -> {
                val heading = when {
                    attrs["style"].orEmpty().contains("H1", true) -> "h1"
                    attrs["style"].orEmpty().contains("H2", true) -> "h2"
                    attrs["style"].orEmpty().contains("H3", true) -> "h3"
                    attrs["style"].orEmpty().contains("H4", true) -> "h4"
                    attrs["style"].orEmpty().contains("H5", true) -> "h5"
                    else -> "p"
                }
                appendBox(attrs, style, "margin")
                appendFont(attrs, style)
                OpenTag("<$heading" + styleAttr(style) + ">", "</$heading>")
            }

            "underline" -> OpenTag("<span style=\"text-decoration:underline\">", "</span>")
            "bold" -> OpenTag("<strong>", "</strong>")
            "italic" -> OpenTag("<em>", "</em>")
            "list" -> OpenTag("<ul>", "</ul>")
            "listitem" -> OpenTag("<li>", "</li>")

            "image", "myimage" -> {
                val src = attrs["source"].orEmpty().trim()
                if (!src.startsWith("http", ignoreCase = true)) return OpenTag("", "")
                val s = StringBuilder()
                appendBox(attrs, s, "margin")
                val fixedH = attrs["height"]?.toFloatOrNull()
                val fixedW = attrs["width"]?.toFloatOrNull()
                when {
                    // 固定高度的缩略图（整合包封面 Height="75" 这种）：按高度缩放、宽度自适应，
                    // **不能**铺满整行 —— 否则整张卡片就只剩一张大图，文字被挤到下面。
                    fixedH != null && fixedH > 0 ->
                        s.append("height:").append(fixedH).append("px;width:auto;")
                    fixedW != null && fixedW > 0 ->
                        s.append("width:").append(fixedW).append("px;height:auto;")
                    // 没写尺寸的大横幅（版本大图那种）才铺满宽度。
                    else -> s.append("width:100%;height:auto;")
                }
                val html = StringBuilder("<img class=\"pic\"")
                html.append(styleAttr(s))
                // 不加 loading="lazy"：WebView 里懒加载在 loadDataWithBaseURL 的页面上
                // 有时不会触发，长页面下方的图片会一直空着。
                html.append(" src=\"").append(escape(src))
                    .append("\" onerror=\"this.style.display='none'\" />")
                OpenTag(html.toString(), "")
            }
            "myhint" -> {
                val text = attrs["text"].orEmpty().cleanResource()
                // 教程：`local:MyHint` 用 `Theme` 指定配色（Blue / Yellow / Red），
                // 老版本还有 IsWarn=True 表示警告。
                val theme = attrs["theme"].orEmpty()
                val cls = when {
                    theme.equals("Red", true) || attrs["iswarn"].equals("true", true) -> "pcl-hint warn"
                    theme.equals("Yellow", true) -> "pcl-hint yellow"
                    else -> "pcl-hint"
                }
                val html = StringBuilder("<div class=\"").append(cls).append('"')
                html.append(styleAttr(styleOfMargin(attrs))).append('>')
                if (text.isNotBlank()) html.append("<p>").append(textHtml(text)).append("</p>")
                OpenTag(html.toString(), "</div>")
            }

            // PCL 的按钮：`MyButton`（有边框）与 `MyIconTextButton`（图标+文字）。
            // 文字都在 `Text` 属性里；WPF 原生 Button 用 `Content`。
            "myicontextbutton", "mybutton", "button" -> {
                val text = attrs["text"].orEmpty().cleanResource()
                    .ifBlank { attrs["content"].orEmpty().cleanResource() }
                val url = eventHref(attrs)
                val colorType = attrs["colortype"].orEmpty()
                val cls = when {
                    colorType.equals("Red", true) -> "btn red"
                    colorType.equals("Highlight", true) -> "btn primary"
                    else -> "btn"
                }
                val open = if (url != null) "<a class=\"$cls\" href=\"${escape(url)}\""
                else "<span class=\"$cls\""
                val html = StringBuilder(open).append(styleAttr(styleOfMargin(attrs))).append('>')
                if (text.isNotBlank()) html.append(textHtml(text))
                OpenTag(html.toString(), if (url != null) "</a>" else "</span>")
            }

            // 「纯文本按钮」：无边框，同样有 Text 属性。
            "mytextbutton" -> {
                val text = attrs["text"].orEmpty().cleanResource()
                val url = eventHref(attrs)
                val open = if (url != null) "<a class=\"link\" href=\"${escape(url)}\""
                else "<span class=\"link\""
                val html = StringBuilder(open).append(styleAttr(styleOfMargin(attrs))).append('>')
                if (text.isNotBlank()) html.append(textHtml(text))
                OpenTag(html.toString(), if (url != null) "</a>" else "</span>")
            }

            // 只有图标的按钮：SVG 画不出来，占一个图标位。
            "myiconbutton" -> OpenTag("<span class=\"icon-btn\"></span>", "")

            // 超链接：内联。
            "hyperlink" -> {
                val url = eventHref(attrs)
                if (url != null) OpenTag("<a class=\"link\" href=\"${escape(url)}\">", "</a>")
                else OpenTag("<span class=\"link\">", "</span>")
            }

            "mylistitem" -> {
                val url = eventHref(attrs)
                val title = attrs["title"].orEmpty().cleanResource()
                val info = attrs["info"].orEmpty().cleanResource()
                val open = if (url != null) "<a class=\"list-item\" href=\"${escape(url)}\""
                else "<div class=\"list-item\""
                val html = StringBuilder(open)
                html.append(styleAttr(styleOfMargin(attrs))).append('>')
                html.append("<span class=\"li-dot\"></span><span class=\"li-main\">")
                if (title.isNotBlank()) html.append("<span class=\"li-title\">").append(textHtml(title)).append("</span>")
                if (info.isNotBlank()) html.append("<span class=\"li-info\">").append(textHtml(info)).append("</span>")
                html.append("</span>")
                OpenTag(html.toString(), if (url != null) "</a>" else "</div>")
            }

            "stackpanel" -> {
                val horizontal = attrs["orientation"].equals("horizontal", ignoreCase = true)
                val html = StringBuilder("<div class=\"")
                html.append(if (horizontal) "row" else "col")
                html.append('"').append(styleAttr(styleOfMargin(attrs))).append('>')
                OpenTag(html.toString(), "</div>")
            }

            "grid" -> {
                // 用 block + 图片 inline-block 近似：多张图片会自然横排，竖向容器也不受影响。
                val s = StringBuilder()
                appendBox(attrs, s, "margin")
                if (attrs["horizontalalignment"] == "center") s.append("text-align:center;")
                OpenTag("<div class=\"grid\"" + styleAttr(s) + ">", "</div>")
            }

            else -> OpenTag("<div class=\"col\"" + styleAttr(styleOfMargin(attrs)) + ">", "</div>")
        }
    }

    private fun textBlock(attrs: Map<String, String>, style: StringBuilder): OpenTag {
        val text = attrs["text"].orEmpty().cleanResource()
        appendBox(attrs, style, "margin")
        appendFont(attrs, style)
        if (attrs["textalignment"].equals("center", true)) style.append("text-align:center;")
        val html = StringBuilder("<p").append(styleAttr(style)).append('>')
        if (text.isNotBlank()) html.append(textHtml(text))
        return OpenTag(html.toString(), "</p>")
    }

    /** 字号 / 颜色 / 背景 / 加粗 / 斜体。 */
    private fun appendFont(attrs: Map<String, String>, style: StringBuilder) {
        attrs["fontsize"]?.toFloatOrNull()?.let { style.append("font-size:").append(it).append("px;") }
        attrs["foreground"]?.let { fg -> mapColor(fg)?.let { style.append("color:").append(it).append(';') } }
        // TextBlock 也支持 Background（教程「主题色」卡片就是给文字加底色）。
        attrs["background"]?.let { bg -> mapColor(bg)?.let { style.append("background:").append(it).append(';') } }
        if (attrs["fontweight"].equals("bold", true) || attrs["fontweight"].equals("heavy", true)) {
            style.append("font-weight:bold;")
        }
        if (attrs["fontstyle"].equals("italic", true)) style.append("font-style:italic;")
        if (attrs["textwrapping"].equals("nowrap", true)) style.append("white-space:nowrap;")
    }

    /** Border：背景 / 圆角 / 边框 / 内边距 / 尺寸 —— 蓝色版本胶囊、图片边框都靠它。 */
    private fun borderStyle(attrs: Map<String, String>): StringBuilder {
        val s = StringBuilder()
        appendBox(attrs, s, "margin")
        appendBox(attrs, s, "padding")
        attrs["background"]?.let { mapColor(it)?.let { c -> s.append("background:").append(c).append(';') } }
        val radius = attrs["cornerradius"]?.split(',')?.firstOrNull()?.trim()?.toFloatOrNull()
        radius?.let { s.append("border-radius:").append(it).append("px;") }
        val thickness = attrs["borderthickness"].orEmpty()
        val brush = attrs["borderbrush"]?.let { mapColor(it) }
        if (thickness.isNotBlank() && brush != null) {
            val parts = thickness.split(',').map { it.trim().toFloatOrNull() ?: 0f }
            if (parts.any { it > 0f }) {
                val maxW = parts.maxOrNull() ?: 1f
                s.append("border:").append(maxW).append("px solid ").append(brush).append(';')
            }
        }
        val width = attrs["width"]?.toFloatOrNull()
        if (width != null && width > 0) s.append("width:").append(width).append("px;")
        val height = attrs["height"]?.toFloatOrNull()
        if (height != null && height > 0) s.append("height:").append(height).append("px;")

        // 【不裁图片】PCL 给版本大图的边框设了 MaxHeight=140 + UniformToFill（居中裁掉
        // 上下），手机上照抄会把图片裁得只剩一条，用户看不到完整画面。这里放弃 max-height，
        // 让图片按原比例完整显示；容器高度随内容走，也就不会再溢出盖住下面的标签和按钮。
        // 只有显式写了固定 Height 且内容不是图片（比如胶囊标签）时才裁。
        if (height != null && height > 0) {
            s.append("overflow:hidden;")
        }
        // 固定宽度且没写水平对齐时，WPF 的默认行为是居中（Stretch + Width）。
        if (width != null && width > 0 && attrs["horizontalalignment"].isNullOrBlank()) {
            s.append("margin-left:auto;margin-right:auto;")
        }
        when (attrs["horizontalalignment"]?.lowercase()) {
            "center" -> s.append("margin-left:auto;margin-right:auto;")
            "right" -> s.append("margin-left:auto;")
        }
        // 负 margin-top = PCL 里「压在上一个元素上面」的做法（版本胶囊 `Margin="0,-20,-1,-2"`
        // 就是这样压在版本大图底部的）。在 HTML 里必须显式抬到上层，否则会被前面的图片盖住
        // 只剩半截 —— 真机上就是「胶囊上半部分被图片吃了」。
        val marginTop = attrs["margin"]?.split(',')?.getOrNull(1)?.trim()?.toFloatOrNull()
        if (marginTop != null && marginTop < 0) {
            s.append("position:relative;z-index:2;")
        }
        return s
    }

    private fun styleOfMargin(attrs: Map<String, String>): StringBuilder {
        val s = StringBuilder()
        appendBox(attrs, s, "margin")
        return s
    }

    /** WPF `Margin/Padding="left,top,right,bottom"` → CSS `top right bottom left`。 */
    private fun appendBox(attrs: Map<String, String>, style: StringBuilder, key: String) {
        val raw = attrs[key]?.takeIf { it.isNotBlank() } ?: return
        val parts = raw.split(',').map { it.trim() }
        // 大额负上边距（PCL 主页里 `Margin="0,-175,0,0"` 用来把标题栏顶到横幅上）
        // 在 HTML 里会把内容直接顶出 `.card`（overflow:hidden），卡片就剩一片空白。
        // 小额的（如版本胶囊的 -20）要保留，所以只截断超过 50 的。
        fun safeTop(top: String): String {
            val v = top.toFloatOrNull() ?: return top
            return if (v < -50f) "0" else top
        }
        val css = when (parts.size) {
            1 -> "$key:${parts[0]}px;"
            2 -> "$key:${safeTop(parts[0])}px ${parts[1]}px;"
            4 -> "$key:${safeTop(parts[1])}px ${parts[2]}px ${parts[3]}px ${parts[0]}px;"
            else -> return
        }
        style.append(css)
    }

    /**
     * 按钮的链接地址。
     *
     * - `EventData` 是网址（打开网页 / 下载文件）→ 直接给网址，由宿主用浏览器打开。
     * - `EventData` 是内部参数（如「启动游戏」+ 版本号）→ 走自定义 scheme
     *   `pclhome://event?type=...&data=...`，由 [PclInlineHtml] 拦下来回传给 Compose。
     */
    private fun eventHref(attrs: Map<String, String>): String? {
        val data = attrs["eventdata"].orEmpty().trim()
        if (data.startsWith("http", ignoreCase = true)) return data
        val type = attrs["eventtype"].orEmpty().trim()
        if (type.isBlank() && data.isBlank()) return null
        val t = java.net.URLEncoder.encode(type, "UTF-8")
        val d = java.net.URLEncoder.encode(data, "UTF-8")
        return "pclhome://event?type=$t&data=$d"
    }

    /** `{StaticResource ColorBrushN}` / `{DynamicResource ...}` / `#RRGGBB` / `#AARRGGBB` → CSS 颜色。 */
    private fun mapColor(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("#")) {
            // 【坑】WPF 的 8 位颜色是 `#AARRGGBB`，CSS 的 8 位是 `#RRGGBBAA`，**顺序相反**。
            // 直接照搬会把「不透明黑 #FF000000」解析成「红色 + 0 alpha」= 全透明，
            // 主页里「📰 欢迎使用新闻主页」这类文字就这么消失了。这里转成 rgba()。
            if (value.length == 9) {
                val a = value.substring(1, 3).toIntOrNull(16) ?: 255
                val r = value.substring(3, 5).toIntOrNull(16) ?: 0
                val g = value.substring(5, 7).toIntOrNull(16) ?: 0
                val b = value.substring(7, 9).toIntOrNull(16) ?: 0
                return if (a == 0) "transparent"
                else "rgba($r,$g,$b,${String.format(java.util.Locale.ROOT, "%.3f", a / 255f)})"
            }
            return value
        }
        Regex("ColorBrush(\\d)").find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return colorBrush(it) }
        Regex("ColorGray(\\d)").find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return grayColor(it) }
        return if (value.contains("Red")) RED else null
    }

    private fun colorBrush(n: Int): String = when (n) {
        1 -> C1
        2 -> C2
        3 -> C3
        4 -> C4
        5 -> C5
        6 -> C6
        7 -> C7
        8 -> C8
        else -> C1
    }

    private fun grayColor(n: Int): String = when (n) {
        1 -> "#404040"
        2 -> GRAY2
        3 -> GRAY3
        4 -> GRAY4
        5 -> "#CCCCCC"
        6 -> "#EBEBEB"
        else -> "#F5F5F5"
    }

    /** 资源引用 / 绑定在手机上取不到实际文案，直接留空（别显示资源名）。 */
    private fun String.cleanResource(): String = if (trim().startsWith("{")) "" else trim()

    private fun styleAttr(style: StringBuilder): String =
        if (style.isEmpty()) "" else " style=\"" + style.toString() + "\""

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * `Text` 属性里的换行。
     *
     * PCL 教程里用 `&#xA;` 在 Text 属性中手动换行，解析后是 `\n`；直接输出会被 CSS
     * 折叠成空格，所以这里换成 `<br>`。元素之间的缩进换行（TEXT 节点）不走这个函数。
     */
    private fun textHtml(text: String): String = escape(text).replace("\n", "<br>")

    // ---------------------------------------------------------------- 页面骨架

    private const val errorCard =
        "<div class=\"card\"><div class=\"card-body\">" +
                "<p>这个主页的内容格式无法识别（可能不是 PCL 主页文件），已停止渲染。</p></div></div>"

    private const val PAGE_CSS = """
        * { box-sizing:border-box; }
        body { margin:0; padding:8px 10px 18px; background:transparent; color:#404040;
               font-family:'Microsoft YaHei','PingFang SC','Noto Sans CJK SC',sans-serif;
               font-size:14px; -webkit-text-size-adjust:100%; }
        .card { background:#FFFFFF; border-radius:5px; overflow:hidden;
                border:1px solid #EDF1F7;
                box-shadow:0 1px 4px rgba(0,0,0,0.08); margin-bottom:12px; }
        .card-head { display:flex; align-items:center; padding:11px 15px;
                     border-bottom:1px solid #F0F4FA; cursor:pointer; user-select:none; }
        .card-title { flex:1; font-size:14px; font-weight:bold; color:#404040; }
        .card-arrow { color:#A6A6A6; font-size:12px; transition:transform .15s; }
        .card-body { padding:10px 15px 14px; }
        /* CanSwap 卡片折叠：内容隐藏、箭头转向。 */
        .card.collapsed .card-body { display:none; }
        .card.collapsed .card-arrow { transform:rotate(-90deg); }
        p { margin:0 0 7px; font-size:14px; line-height:1.7; word-break:break-word; }
        h1 { font-size:22px; margin:12px 0 8px; font-weight:bold; color:#1370F3; }
        h2 { font-size:20px; margin:12px 0 6px; font-weight:bold; color:#1370F3; }
        h3 { font-size:17px; margin:11px 0 5px; font-weight:bold; color:#1370F3; }
        h4 { font-size:15px; margin:10px 0 4px; font-weight:bold; color:#1370F3; }
        h5 { font-size:14px; margin:8px 0 3px; font-weight:bold; color:#4890F5; }
        strong { font-weight:bold; }
        em { font-style:italic; }
        code { background:#EAF2FE; border:1px solid #D5E6FD; border-radius:4px;
               padding:0 4px; font-family:Consolas,monospace; font-size:13px; color:#404040; }
        ul { margin:6px 0 8px; padding-left:21px; }
        li { font-size:14px; line-height:1.7; margin:3px 0; word-break:break-word; }
        /* 图片尺寸由元素行内样式决定（有 Height 的缩略图按高度、大横幅铺满），
           这里只兜底「不超过容器宽」和圆角，不再强制 width。 */
        .pic { max-width:100%; height:auto; border-radius:6px; display:block; margin:6px 0; }
        .grid { display:flex; flex-wrap:wrap; align-items:flex-start; gap:6px; }
        .grid > .pic { flex:1 1 0; min-width:0; max-width:100%; object-fit:contain; }
        .grid > * { max-width:100%; }
        .col { display:block; }
        .row { display:flex; flex-direction:row; align-items:center; flex-wrap:wrap; gap:6px; }
        /* 横排里的文字块占满剩余宽度（整合包卡片：左边封面图、右边标题+简介）。 */
        .row > .col { flex:1 1 0; min-width:0; }
        .btn { display:inline-flex; align-items:center; gap:4px; padding:5px 11px;
               margin:3px 6px 3px 0; font-size:13px; color:#404040;
               background:rgba(255,255,255,0.65); border:1px solid #C8D8EE; border-radius:4px; }
        /* 教程里的 ColorType：Highlight = 主题色实心，Red = 红色警告。 */
        .btn.primary { background:#1370F3; border-color:#1370F3; color:#FFFFFF; }
        .btn.red { background:#FBDDDD; border-color:#E8A5A5; color:#CE2111; }
        .icon-btn { display:inline-block; width:16px; height:16px; vertical-align:middle; }
        .link { color:#1370F3; }
        a { text-decoration:none; color:inherit; }
        a.link { color:#1370F3; }
        .list-item { display:flex; align-items:flex-start; padding:7px 2px; }
        .li-dot { width:6px; height:6px; border-radius:3px; background:#1370F3;
                  margin:7px 9px 0 0; flex:none; }
        .li-main { display:flex; flex-direction:column; min-width:0; }
        .li-title { font-size:14px; color:#404040; }
        .li-info { font-size:12px; color:#737373; margin-top:1px; }
        .pcl-hint { background:#EAF2FE; border-left:3px solid #1370F3; border-radius:2px;
                    padding:8px 12px; margin:6px 0; }
        .pcl-hint p { font-size:13px; margin:0; }
        .pcl-hint.warn { background:#FBDDDD; border-left-color:#CE2111; }
        .pcl-hint.yellow { background:#FFF8E1; border-left-color:#F2B01E; }
        .sep { display:flex; align-items:center; margin:12px 2px; color:#4890F5;
               font-size:14px; font-weight:bold; letter-spacing:1px; }
        .sep::before, .sep::after { content:''; flex:1; height:1.5px;
                                    background:#4890F5; opacity:0.55; }
        .sep span { padding:0 12px; white-space:nowrap; }
    """

    private fun wrapPage(body: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <!-- 不少主机（如 i.sodamc.com）会对带 Referer 的图片请求返回 403，
             而 WebView 加载图片默认带当前页 URL 的 Referer。改成 no-referrer 后即可正常显示。 -->
        <meta name="referrer" content="no-referrer">
        <style>$PAGE_CSS</style></head><body>$body</body></html>
    """.trimIndent()
}
