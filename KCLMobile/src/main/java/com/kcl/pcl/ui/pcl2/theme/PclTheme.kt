/*
 * KCL Mobile — PCL2 UI port
 *
 * 文字规范与尺寸常量，取自 PCL2 `Application.xaml` 的 TextBlock 全局样式
 * （FontSize 13 / ColorBrush1 / CharacterEllipsis / LaunchFontFamily）与
 * `FormMain.xaml` 的窗口尺寸（850 × 500，标题栏 48，边框圆角 6）。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.kcl.pcl.ui.pcl2.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PCL2 的全局字号规范。
 *
 * PCL2 用 `LaunchFontFamily`（Windows 上解析为“微软雅黑 UI”），
 * Android 上没有同名字体，这里使用系统默认无衬线字体作为最接近的替代，
 * 字号与字重保持完全一致。
 */
object PclTextStyle {
    val fontFamily: FontFamily = FontFamily.SansSerif

    /**
     * 文本语言：钉成简体中文。
     *
     * Android 的 CJK 回退字体是按「文本语言」挑字形的（`NotoSansCJK` 里同一个码位存有
     * 简中 / 繁中 / 日 / 韩几套字形）。设备语言是英文或日文时，不写这一项的话
     * 「直 / 真 / 骨 / 令 / 者 / 与 / 决」这类字会挑到日文字形 —— 中文用户看着就是
     * 「字体显示不对」。PCL 是中文程序，这里写死简体中文。
     */
    private val pclLocales = LocaleList(Locale("zh-Hans"))

    /**
     * 行高系数。
     *
     * PCL 的 WPF 不会因为行高不足而裁剪中文 —— 超出部分照画；Compose 的 `Text` 默认
     * `TextOverflow.Clip`，**会**把超出文本框的字裁掉。Android 的中文回退字形本身又比
     * 13sp 的字身更高（系统给 CJK 回退字体加了约 1.1 倍的字号补偿），
     * 于是「导出内容」那些选项行里的中文下半截被整整齐齐削掉。
     *
     * 这里统一按字身的 1.4 倍给行高（WPF 的默认行距也是 1.4 上下），
     * 再配合组件里的 `TextOverflow.Visible`，两边都不会再裁字。
     */
    const val LINE_HEIGHT_SCALE = 1.4f

    /** 给某个字号算行高。 */
    fun lineHeight(fontSize: Int) = (fontSize * LINE_HEIGHT_SCALE).sp

    /** 全局默认：13 号、ColorBrush1、超出省略。 */
    @Composable
    fun base(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 13.sp,
        lineHeight = lineHeight(13),
        color = p.color1,
    )

    /** 卡片标题：13 号加粗。 */
    @Composable
    fun cardTitle(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 13.sp,
        lineHeight = lineHeight(13),
        fontWeight = FontWeight.Bold,
        color = p.color1,
    )

    /** 列表项标题。 */
    @Composable
    fun listTitle(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 13.sp,
        lineHeight = lineHeight(13),
        color = p.color1,
    )

    /** 列表项副标题（灰色小字）。 */
    @Composable
    fun listInfo(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 12.sp,
        lineHeight = lineHeight(12),
        color = p.gray2,
    )

    /** 按钮文字：13 号居中。 */
    @Composable
    fun button(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 13.sp,
        lineHeight = lineHeight(13),
        color = p.color1,
    )

    /**
     * 提示条文字。
     *
     * 注：PCL 的 `MyHint` 写死 `LineHeight=16`，但那是 13px 字身在 WPF 下的口径；
     * Compose 会按 16sp 裁字，所以这里仍然用 [LINE_HEIGHT_SCALE]（结构、配色、内边距不变）。
     */
    @Composable
    fun hint(p: PclPalette = LocalPclPalette.current) = TextStyle(
        fontFamily = fontFamily,
        localeList = pclLocales,
        fontSize = 13.sp,
        lineHeight = lineHeight(13),
        color = p.color1,
    )
}

/**
 * PCL2 的尺寸常量（WPF 的设备无关像素与 Compose 的 dp 一一对应）。
 *
 * 每个数值后面都标注了它在 PCL2 源码中的出处，方便逐项核对。
 */
/**
 * PCL 里所有 `BorderThickness="1"` 的描边都只有 **1 个单位、且不吸附设备像素**
 * （见 `MyButton.xaml`：`UseLayoutRounding="False" SnapsToDevicePixels="False"`），
 * 在 100% 缩放下渲染出来是一条发丝线。
 *
 * 手机上如果直接画 `1.dp`，在 density 2~3 的屏幕上就是 2~3 个物理像素、明显更粗，
 * 所以统一按「1 个物理像素」来画（按钮 / 输入框 / 下拉框 / 列表勾选框的描边都用它）。
 */
@Composable
fun pclHairline(): Dp = with(LocalDensity.current) { 1.dp / density }

object PclDimens {
    /** `FormMain.xaml`：Width="850" Height="500" */
    val windowWidth: Dp = 850.dp
    val windowHeight: Dp = 500.dp

    /** 窗口外框 `PanBack Margin="10"` */
    val windowMargin: Dp = 10.dp

    /** `BorderForm Margin="8"` + `RectForm RadiusX/Y="6"` */
    val formMargin: Dp = 8.dp
    val formCorner: Dp = 6.dp

    // ---------------------------------------------------------------- 标题栏

    /** `PanTitle Height="48"` */
    val titleBarHeight: Dp = 48.dp

    /**
     * `ShapeTitleLogo`：`Width="39"`、`Margin="19,15.5,0,15.5"`、`StrokeThickness="2.2"`。
     * 高度 = 48 − 15.5 × 2 = 17。
     */
    val titleLogoWidth: Dp = 39.dp
    val titleLogoHeight: Dp = 17.dp
    val titleLogoStart: Dp = 19.dp

    /**
     * 标题栏两个窗口按钮（`BtnTitleClose` / `BtnTitleMin`）：
     * 均 `Width/Height="28"`、`VerticalAlignment="Center"`；
     * 关闭 `Margin="0,0,12,0"`、最小化 `Margin="0,0,44,0"` → 两者间距 4。
     */
    val titleButtonSize: Dp = 28.dp
    val titleButtonMarginEnd: Dp = 12.dp
    val titleButtonGap: Dp = 4.dp

    /** 子页面返回按钮 `BtnTitleInner Margin="12,0,0,0"`，标题 `Margin="47,1,60,0"`。 */
    val titleInnerBackStart: Dp = 12.dp
    val titleInnerTitleStart: Dp = 47.dp

    /**
     * 导航胶囊 `PanTitleSelect`：`Height="27"`、`Margin="13,0"`；
     * 每个 `MyRadioButton Margin="5,0" Padding="2,0"`（→ 间距 10）；
     * 内部 `ShapeLogo Margin="12,0,0,0"`、`MaxWidth/Height="16"`；
     * `LabText Margin="8,0,12,0"`。
     */
    val navPillHeight: Dp = 27.dp
    val navPillCorner: Dp = 13.5.dp
    val navBarSideMargin: Dp = 13.dp
    val navPillSpacing: Dp = 10.dp
    val navPillLogoStart: Dp = 12.dp
    val navPillLogoSize: Dp = 16.dp
    val navPillTextStart: Dp = 8.dp
    val navPillTextEnd: Dp = 12.dp

    // ---------------------------------------------------------------- 侧栏

    /** `PageLaunchLeft Width="300"`、`PageSelectLeft MyScrollViewer MaxWidth="300" MinWidth="250"` */
    val leftPanelMaxWidth: Dp = 300.dp
    val leftPanelMinWidth: Dp = 250.dp

    /**
     * `PageLaunchLeft Width="300"`；其余页面的左栏**按内容宽度**
     * （`PanMain` 第一列是 `Auto`，宽度 = 最宽条目，见 `pclLeftPanelWidth`）。
     * 这里的 200 只作为「条目清单缺失」时的兜底值。
     */
    val leftPanelWidth: Dp = 300.dp
    val leftPanelNarrowWidth: Dp = 200.dp

    /**
     * 左栏条目的标题字号。
     *
     * 出处：`Controls/MyListItem.xaml.vb:191`
     * ```
     * Public Shared ReadOnly FontSizeProperty As DependencyProperty =
     *     DependencyProperty.Register("FontSize", GetType(Double), GetType(MyListItem),
     *                                 New PropertyMetadata(CType(14, Double)))
     * ```
     * `MyListItem` 自己注册了一个默认 14 的 `FontSize`，而 `MyListItem.xaml` 里
     * `LabTitle` 的 `FontSize="{Binding FontSize, ElementName=PanBack}"` 绑的就是它，
     * 所以**没写 FontSize 的列表项一律是 14**（`Application.xaml` 那个 TextBlock 的 13 是
     * 样式 setter，优先级低于 LabTitle 上的本地绑定，管不到这里）。
     *
     * **这个常量同时用于「渲染」与「算左栏宽度」**（见 `pclLeftPanelWidth`）——
     * 两边必须同源，否则算出来的宽度和实际排版差几个 dp，最后一个字就会被裁掉
     * （「原版游戏」显示成「原版游」就是这么来的）。
     */
    val leftItemFontSize: Int = 14

    /** 左栏条目 `Height="36"`、起始留白 `Margin="0,12,0,0"`。 */
    val sideItemHeight: Dp = 36.dp
    val sideListTop: Dp = 12.dp

    /** `RectLeftShadow Width="4"`、`LinearGradientBrush Opacity="0.04"`。 */
    val leftShadowWidth: Dp = 4.dp

    // ---------------------------------------------------------------- 内容区

    /** 右页根 `StackPanel Margin="25,10"`（取整页统一口径）。 */
    /**
     * 右侧页面根容器的外边距。PCL 各页写成 `Margin="25,25,25,10"`
     * （`PageLaunchRight.xaml:8`、`PageOtherHelp.xaml:9` 等，全项目出现 7 次，是最常见的一组）。
     * 注意上边距是 **25**（内容区在 48 高的标题栏之下），下边距才是 10。
     */
    val contentTop: Dp = 25.dp
    val contentSide: Dp = 25.dp
    val contentBottom: Dp = 10.dp

    /** 卡片外边距最常见的口径 `Margin="0,0,0,15"`。 */
    val cardGap: Dp = 15.dp

    /** `MyCard`：`CornerRadius=5`、底色 `#F5FFFFFF`、标题 `Margin="15,12,0,0"`、折叠高 40。 */
    val cardCorner: Dp = 5.dp
    val cardTitleX: Dp = 15.dp
    val cardTitleY: Dp = 12.dp
    val cardCollapsedHeight: Dp = 40.dp

    /**
     * 卡片内容边距。PCL 的 `MyCard` 本身**不给内容任何边距**（子控件直接放进 MainGrid），
     * 边距全部由各页在卡片内自行书写。统计 Pages/ 下所有 `Margin="X,40,X,Y"`：
     * `25,40,25,15` 出现 12 次（最多）、`20,40,18,15` 7 次、`25,40,25,20` 4 次、`25,40,25,17` 2 次，
     * 其余为零星个例。故此处取出现最多的 `25,40,25,15` 作为默认值。
     */
    val cardContentStart: Dp = 25.dp
    val cardContentTop: Dp = 40.dp
    val cardContentEnd: Dp = 25.dp
    val cardContentBottom: Dp = 15.dp

    /** 内容较密的页面（关于/下载详情）用 `Margin="21,40,21,16"`。 */
    val cardContentStartTight: Dp = 21.dp
    val cardContentEndTight: Dp = 21.dp
    val cardContentBottomTight: Dp = 16.dp

    // ---------------------------------------------------------------- 控件

    /** `MyButton`：常见 `Height="35"`、`CornerRadius=3`、`BorderThickness=1`、`Padding="13,0"`。 */
    val buttonHeight: Dp = 35.dp
    val buttonCorner: Dp = 3.dp
    val buttonPaddingH: Dp = 13.dp

    /** 启动按钮 `BtnLaunch Height="54" Margin="20,0" Padding="30,0,30,15"`。 */
    val launchButtonHeight: Dp = 54.dp

    /** `MyListItem` 默认 `Height="42"`、默认 `FontSize=14`。 */
    val listItemHeight: Dp = 42.dp
    val listItemFontSize: Int = 14

    /** `MyCheckBox`：`18×18`、`BorderThickness=1.1`、`CornerRadius=3`、对勾 `12×12`、文字 `Margin="26,0,0,0"`。 */
    val checkBoxSize: Dp = 18.dp
    val checkBoxCorner: Dp = 3.dp
    val checkBoxBorder: Dp = 1.1.dp
    val checkBoxLogo: Dp = 12.dp
    val settingTextStart: Dp = 26.dp

    /** `MyRadioBox`：`18×18` 圆、`BorderThickness=1.1`、选中圆点 `9×9`。 */
    val radioBoxSize: Dp = 18.dp
    val radioBoxBorder: Dp = 1.1.dp
    val radioBoxDot: Dp = 9.dp

    /** `MyTextBox`：`RowDefinition MinHeight="28"`、`CornerRadius=3`、提示文字 `Margin="7,0,0,0"`。 */
    val textBoxMinHeight: Dp = 28.dp
    val textBoxHintStart: Dp = 7.dp

    /** `MyComboBox`：内容 `Margin="8.5,0,21.5,0"`，箭头 `Path Width="15"`、`StrokeThickness=1.5`。 */
    val comboContentStart: Dp = 8.5.dp
    val comboContentEnd: Dp = 21.5.dp
    val comboArrowWidth: Dp = 15.dp

    /** `MySlider`：`MinHeight=16`、底轨 `StrokeThickness=1`、前轨 2、圆点 `10×10` 描边 1.2。 */
    val sliderHeight: Dp = 16.dp
    val sliderTrackBackThickness: Dp = 1.dp
    val sliderTrackForeThickness: Dp = 2.dp
    val sliderDotSize: Dp = 10.dp
    val sliderDotStroke: Dp = 1.2.dp

    /** `MyHint`：左边 3 色条、`CornerRadius=2`、文字 `Padding="12,9"`、`LineHeight=16`、关闭键 20×20。 */
    val hintBarWidth: Dp = 3.dp
    val hintCorner: Dp = 2.dp
    val hintPaddingH: Dp = 12.dp
    val hintPaddingV: Dp = 9.dp
    val hintLineHeight: Dp = 16.dp

    /** 左下角提示条栈 `PanHint Margin="0,0,0,20"`。 */
    val hintStackBottom: Dp = 20.dp
    val hintWidth: Dp = 300.dp

    /**
     * `MyExtraButton`：`Width="40"`，高度动画目标 50（圆形本体在下方 40×40）。
     * 右下角容器 `StackPanel Margin="15"`。
     */
    val extraButtonSize: Dp = 40.dp
    val extraButtonStackMargin: Dp = 15.dp
    val extraButtonGap: Dp = 8.dp

    /** `MyScrollBar Width="8"`、指示块 `CornerRadius=3`、`Margin=2`。 */
    val scrollBarWidth: Dp = 8.dp
    val scrollBarThumbCorner: Dp = 3.dp
    val scrollBarThumbInset: Dp = 2.dp

    // ---------------------------------------------------------------- 对话框（MyMsg*）

    /**
     * `MyMsgText` / `MyMsgInput` / `MyMsgSelect` 三个对话框共用**完全相同**的骨架：
     *
     * ```
     * Grid MinWidth=400 Margin=25 HorizontalAlignment=Center
     *  └ Border PanBorder  CornerRadius=7  Background=#FBFBFB
     *     （DropShadowEffect Color=ColorObject1 BlurRadius=20 ShadowDepth=4 Opacity=0.8）
     *     └ Grid PanMain Margin="22,22,22,23"  行定义 Auto / 2 / 13 / …/ Auto
     *        ├ Row0  LabTitle  FontSize=23 Foreground=ColorBrush2 Margin="7,-1,70,9"
     *        ├ Row1  ShapeLine Height=2 Fill=(标题前景色 ColorBrush2)
     *        ├ Row2  固定 13 高间距
     *        ├ Row3  正文（FontSize=15 LineHeight=18 #FF5C5C5C，容器 Padding="7,0,15,0"）
     *        └ Row4  PanBtn 右对齐 Margin="150,0,8,0"
     * ```
     *
     * 三份 XAML 的 Row0~Row2 与 PanBtn 逐字符一致，只有 Row3 的内容不同。
     */
    val msgCorner: Dp = 7.dp
    /** 对话框距屏幕边缘的 `Margin="25"`。 */
    val msgMargin: Dp = 25.dp
    /**
     * 桌面版是 `MinWidth=400`；手机上 400dp 会超出竖屏宽度，故改为「最大 460 + 左右各留 25」，
     * 平板封顶 460、手机自然铺满。这是对话框唯一的一处尺寸妥协。
     */
    val msgMaxWidth: Dp = 460.dp

    val msgPaddingStart: Dp = 22.dp
    val msgPaddingTop: Dp = 22.dp
    val msgPaddingEnd: Dp = 22.dp
    val msgPaddingBottom: Dp = 23.dp

    /** 标题 `LabTitle`：`FontSize=23`、`Foreground=ColorBrush2`、`Margin="7,-1,70,9"`。 */
    val msgTitleFontSize: Int = 23
    val msgTitleStart: Dp = 7.dp
    val msgTitleEnd: Dp = 70.dp
    val msgTitleBottom: Dp = 9.dp

    /** 标题下的分隔线 `ShapeLine Height="2"`，填充与标题同色。 */
    val msgLineHeight: Dp = 2.dp
    /** 分隔线到正文的间距（行定义里那个 `Height="13"` 的行）。 */
    val msgLineGap: Dp = 13.dp

    /** 正文：`FontSize=15`；TextBlock `Padding="1"` 叠加在容器 `Padding="7,0,15,0"` 之上。 */
    val msgBodyFontSize: Int = 15
    val msgBodyLineHeight: Int = 18
    val msgBodyStart: Dp = 8.dp
    val msgBodyEnd: Dp = 16.dp

    /** 正文到按钮行的间距：`MyMsgText` 的 `PanCaption Margin="0,0,0,17"`（+ 文本 Padding 1）。 */
    val msgBodyBottomTight: Dp = 18.dp
    /** `MyMsgInput` 的 `PanText Margin="0,0,0,7"`（+ 文本 Padding 1），因为它下面还有输入框。 */
    val msgBodyBottomLoose: Dp = 8.dp

    /** `MyMsgInput` 的 `TextArea Margin="7,0,7,12"`。 */
    val msgInputStart: Dp = 7.dp
    val msgInputEnd: Dp = 7.dp
    val msgInputBottom: Dp = 12.dp

    /**
     * `PanBtn`：右对齐、`Margin="150,0,8,0"`。右对齐时左侧 150 只表示「别挤到最左边」，
     * 实际生效的是右边距 8；按钮彼此 `Margin="12,0,0,0"`。
     */
    val msgButtonGap: Dp = 12.dp
    val msgButtonEnd: Dp = 8.dp
    /** 对话框里的 `MyButton Padding="5,0"`（普通按钮是 13）。 */
    val msgButtonPaddingH: Dp = 5.dp

    /**
     * 对话框里的 `MyButton TextPadding="7"`。
     *
     * 出处：`Controls/MyMsg/MyMsgText.xaml:32-34`（`MyMsgInput` / `MyMsgSelect` 第 34/31 行、
     * `Pages/PageLaunch/MyMsgLogin.xaml:32-36` 写法完全一致）——
     * 每个按钮都同时写着 `TextPadding="7"` 与 `Padding="5,0"`。
     *
     * `MyButton.xaml.vb:28-33` 把 `TextPadding` 绑到 `LabText.Padding`（文字四周各 7），
     * `:52-59` 把 `Padding` 绑到内层 `PanFore.Padding`（左右各 5）。
     * 两者叠加才是 PCL2 里对话框按钮的横向留白：**5 + 7 = 12**（普通按钮是 13）。
     *
     * 之前只实现了 `Padding`，横向留白少了一半，于是「确定」这种两字按钮被压成
     * 近正方形（实测 36×35dp）；PCL2 里它应该是 **52×34dp** 左右。
     */
    val msgButtonTextPadding: Dp = 7.dp
}

/**
 * 主题包裹：向子树注入 PCL2 调色板。
 *
 * 注意这里【不】套用 Material 主题，因为 PCL2 的界面完全由自己的资源字典驱动，
 * 任何 Material 默认样式都会污染还原度。
 */
@Composable
fun PclTheme(
    palette: PclPalette = PclPalettes.blue,
    /**
     * 界面缩放系数（PCL 的「界面缩放」，对应 Windows 的显示缩放）。
     *
     * 乘进 density 而不是 fontScale：这样字与框**一起**放大，跟 PCL 在 125%/150%
     * 显示缩放下的行为一致；也保证 1 设计像素始终是整数个物理像素，中文笔画不会被栅格化抹掉。
     */
    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // PCL 是桌面程序：界面尺寸全是固定 DIP，WPF 在 125%/150% 缩放下**字与框一起放大**。
    // Android 的 sp 只放大文字、不会动 dp，系统「字体大小」一旦大于标准，
    // 固定高的容器（比如导出页 26dp 的选项行）就会把字的下半截裁掉 —— 也就是「字体显示不全」。
    // 因此这里把 fontScale 钉成 1，让 sp 与 dp 同步，等于始终按 PCL 的 100% 缩放渲染。
    //
    // 还有第二个「字体显示不全」的来源：PCL 的全部尺寸都是按「1 设计单位 = 1 像素 @100%」写的
    // （见 PclDimens 里逐条标注的 XAML 数值），而本移植把它当成 dp 用。有些设备/模拟器的
    // density 小于 1（实测 0.92：胶囊 27dp 只画了 25px、折叠卡片 40dp 只画了 37px、标题栏 48dp
    // 只画了 44px），此时 1dp 不到 1 个物理像素，13 号中文被压成 12px ——
    // 「实」的宝盖头点、「选」的辶、「筛」的竹字头这类细笔画会在栅格化时直接消失，
    // 看起来正是「字体显示不全」。所以 density 也抬到至少 1，
    // 保证任何一处文字都至少按 1 设计像素 = 1 物理像素渲染（等于 PCL 在 100% 缩放下的样子）。
    val current = LocalDensity.current
    val fixedDensity = remember(current.density, current.fontScale, uiScale) {
        Density(density = current.density.coerceAtLeast(1f) * uiScale, fontScale = 1f)
    }
    CompositionLocalProvider(
        LocalPclPalette provides palette,
        LocalDensity provides fixedDensity,
    ) {
        content()
    }
}

/** 供预览用：可切换主题的 PclTheme。 */
@Composable
fun PclThemeSwitchable(
    initial: PclPalette = PclPalettes.blue,
    content: @Composable (PclPalette, (PclPalette) -> Unit) -> Unit,
) {
    var palette by remember { mutableStateOf(initial) }
    PclTheme(palette) {
        content(palette) { palette = it }
    }
}

/** 常用颜色别名，避免到处写 `LocalPclPalette.current.xxx`。 */
val pclPalette: PclPalette
    @Composable get() = LocalPclPalette.current

/** 把颜色按透明度包裹（PCL 里常用 `New MyColor(Alaph, Color)`）。 */
fun Color.withAlpha255(alpha: Int): Color = copy(alpha = alpha / 255f)
