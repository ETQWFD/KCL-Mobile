/*
 * KCL Mobile — PCL2 UI port
 *
 * `MyResourceItem` 的 Compose 实现（PCL 社区资源列表里的每一项）。
 *
 * 几何完全照抄 `Pages/PageDownload/Resource/MyResourceItem.xaml`：
 *   Grid Height=64
 *   列：7 / 50 / 8 / Auto / 1*
 *   行：1*(约6.2) / 17 / 18 / 16 / 1.1*(约6.8)
 *   · Logo：50×50、圆角 6、十字居中于中间三行（高 51）
 *   · 标题：FontSize 14、ColorBrush1，行 1 底对齐
 *   · 副标题：FontSize 12、ColorBrushGray1、Opacity 0.4，紧跟标题（LabTitleRaw）
 *   · 类别标签：底 #11000000、Padding 3,1、圆角 3、右边距 3，文字 11 号 #868686
 *   · 描述：FontSize 12、ColorBrushGray3，行 2 底对齐（比标签低 0.5）
 *   · 底栏：版本(ColorBrushGray3) / 下载量 / 更新时间 / 来源(后三者 ColorBrushGray4)，
 *          图标高 11 / 10.5 / 11.5 / 11.5，文字 12 号
 * 指向底色来自 `RefreshColor`：空闲 ColorBrush7（Opacity 0）→ 指向 ColorBrushBg1
 * （= ColorBrush7 @ alpha 190）→ 按下 ColorBrush6；圆角 3、边框 1px ColorBrush6。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kcl.pcl.ui.pcl2.theme.PclTextStyle
import com.kcl.pcl.ui.pcl2.theme.pclPalette
import com.kcl.pcl.ui.screens.content.download.assets.elements.AssetsIcon
import com.kcl.pcl.ui.pcl2.theme.pclHairline

/**
 * PCL 社区资源列表项。
 *
 * @param title 标题（PCL 的 `LabTitle`）
 * @param subtitle 副标题（PCL 的 `LabTitleRaw`，通常为译名对应的原名）
 * @param description 描述（PCL 的 `LabInfo`）
 * @param tags 类别标签（PCL 的 `PanTags` 内容）
 * @param iconUrl 项目图标链接；为空时用 [iconFallback]
 * @param versionText 支持的游戏版本 / 加载器（PCL 的 `LabVersion`）
 * @param downloadText 下载量（PCL 的 `LabDownload`）
 * @param updateText 最近更新（PCL 的 `LabTime`）
 * @param sourceText 来源平台（PCL 的 `LabSource`）
 */
@Composable
fun PclResourceItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    description: String = "",
    tags: List<String> = emptyList(),
    iconUrl: String? = null,
    iconFallback: ImageVector? = null,
    versionText: String? = null,
    downloadText: String? = null,
    updateText: String? = null,
    sourceText: String? = null,
    maxTags: Int = 3,
    onClick: (() -> Unit)? = null,
) {
    val p = pclPalette
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    // MyResourceItem.RefreshColor：指向时底色换成 ColorBrushBg1（= ColorBrush7 @ alpha 190），
    // 按下换成实色 ColorBrush6，离开时淡回透明（ColorBrush7 + Opacity 0 == 看不见）。
    val active = hovered || pressed
    val background by animateColorAsState(
        targetValue = when {
            pressed -> p.color6
            active -> p.color7.copy(alpha = 190f / 255f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "pclResourceItemBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) p.color6 else Color.Transparent,
        animationSpec = tween(120),
        label = "pclResourceItemBorder",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            // PCL 的行高是 64（行高 17/18/16 + 上下留白）；这里用 minHeight，
            // 系统字号调大时让它自然长高，而不是把最后一行「底栏」裁掉
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(background)
            .border(pclHairline(), borderColor, RoundedCornerShape(3.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(7.dp))

            // 图标列：宽 50，Logo 50×50 圆角 6，垂直居中
            Box(
                modifier = Modifier.width(50.dp).heightIn(min = 64.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                PclResourceIcon(iconUrl = iconUrl, iconFallback = iconFallback)
            }

            Spacer(Modifier.width(8.dp))

            // 行 1/2/3：上下各留出 1* 与 1.1* 的空行（约 6.2 / 6.8）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 7.dp, bottom = 8.dp, end = 3.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    // PCL 里标题与副标题是两个 TextBlock（LabTitle 14 号 ColorBrush1 +
                    // LabTitleRaw 12 号 Gray1@0.4）；这里合成一个 AnnotatedString，
                    // 让「标题过长」时的省略号能自然地作用在整行文字上。
                    text = if (subtitle.isNullOrBlank()) {
                        AnnotatedString(title)
                    } else {
                        buildAnnotatedString {
                            append(title)
                            withStyle(
                                SpanStyle(
                                    fontSize = 12.sp,
                                    color = p.gray1.copy(alpha = 0.4f),
                                ),
                            ) {
                                append("  |  ")
                                append(subtitle)
                            }
                        }
                    },
                    style = PclTextStyle.base(p).copy(fontSize = 14.sp, lineHeight = PclTextStyle.lineHeight(14), color = p.color1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    tags.take(maxTags).forEach { tag ->
                        PclResourceTag(tag)
                    }
                    Text(
                        text = description,
                        style = PclTextStyle.base(p).copy(fontSize = 12.sp, lineHeight = PclTextStyle.lineHeight(12), color = p.gray3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!versionText.isNullOrBlank()) {
                        PclResourceInfo(
                            icon = PclIcons.resourceVersion,
                            iconHeight = 11.dp,
                            text = versionText,
                            color = p.gray3,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (!downloadText.isNullOrBlank()) {
                        Spacer(Modifier.width(10.dp))
                        PclResourceInfo(
                            icon = PclIcons.resourceDownload,
                            iconHeight = 10.5.dp,
                            text = downloadText,
                            color = p.gray4,
                        )
                    }
                    if (!updateText.isNullOrBlank()) {
                        Spacer(Modifier.width(10.dp))
                        PclResourceInfo(
                            icon = PclIcons.resourceTime,
                            iconHeight = 11.5.dp,
                            text = updateText,
                            color = p.gray4,
                        )
                    }
                    if (!sourceText.isNullOrBlank()) {
                        Spacer(Modifier.width(10.dp))
                        PclResourceInfo(
                            icon = PclIcons.resourceSource,
                            iconHeight = 11.5.dp,
                            text = sourceText,
                            color = p.gray4,
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 50×50、圆角 6 的项目图标；无链接时退回 PCL 图标或默认未知图标。 */
@Composable
private fun PclResourceIcon(iconUrl: String?, iconFallback: ImageVector?) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier.size(50.dp).clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl.isNullOrBlank() && iconFallback != null) {
            val p = pclPalette
            Icon(
                imageVector = iconFallback,
                contentDescription = null,
                tint = p.gray2,
                modifier = Modifier.size(24.dp),
            )
        } else {
            AssetsIcon(
                modifier = Modifier.clip(shape),
                size = 50.dp,
                iconUrl = iconUrl,
            )
        }
    }
}

/** 类别标签：底 #11000000、Padding 3,1、圆角 3、右边距 3、文字 11 号 #868686。 */
@Composable
private fun PclResourceTag(text: String) {
    Box(
        modifier = Modifier
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x11000000))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = PclTextStyle.base(pclPalette).copy(fontSize = 11.sp, lineHeight = PclTextStyle.lineHeight(11), color = Color(0xFF868686)),
            maxLines = 1,
        )
    }
}

/** 底栏的一项：小图标 + 12 号文字（图标与文字间距 4/5）。 */
@Composable
private fun PclResourceInfo(
    icon: ImageVector,
    iconHeight: Dp,
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(iconHeight).width(iconHeight),
        )
        Text(
            text = text,
            style = PclTextStyle.base(pclPalette).copy(fontSize = 12.sp, lineHeight = PclTextStyle.lineHeight(12), color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}
