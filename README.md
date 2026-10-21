<div align="center">
    <img width="96" src="/FCL/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"></img>
</div>

<h1 align="center">KCL（Android 手机版）</h1>

<div align="center">

[![Android CI](https://github.com/ETQWFD/KCL-Mobile/actions/workflows/continuous.yml/badge.svg)](https://github.com/ETQWFD/KCL-Mobile/actions/workflows/continuous.yml)
[![Release](https://img.shields.io/github/v/release/ETQWFD/KCL-Mobile?style=flat-square&color=0b7a46)](https://github.com/ETQWFD/KCL-Mobile/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-0b7a46?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.html)

</div>

简体中文 | [English](./README_EN.md)

当常见启动器因为网络、维护或外部压力暂时不可用时，玩家仍需要一个能进入本地游戏的备选入口。**KCL** 在这种情况下延续版本下载、正版登录和本地实例管理能力，这是 KCL 系列的 Android 手机版本。

KCL（Android）基于 [Fold Craft Launcher（FCL）](https://github.com/FCL-Team/FoldCraftLauncher) 再发行。FCL 由 [FCL-Team](https://github.com/FCL-Team) 基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的核心功能、使用 [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) 后端开发。KCL 是独立的第三方修改版，由 **龙腾OS / ETC Team** 修改并维护；本仓的更新检查与启动公告均指向 ETQWFD 自有仓库，与上游 FCL-Team 无运营关联。

---

## 下载

- 官网下载页（与桌面版 KCL2 / KCL CE 同一站点）：https://etqwfd.github.io/KCL2/
- APK（全架构通用，可直接安装）：https://github.com/ETQWFD/KCL-Mobile/releases
- 要求 Android 8.0 及以上（minSdk 26），安装后首次运行会按需补全 Java 运行时。

---

## 核心特性

- 原生支持 Minecraft: Java Edition 全版本（含最新快照），支持版本隔离、整合包导入。
- 模组加载器：Forge / NeoForge / Fabric / Quilt / OptiFine / LiteLoader 等。
- 内置多版本 Java 运行时（Java 8 / 17 / 21 / 25），也支持自行导入。
- 虚拟鼠标、自定义按键映射与控制布局；光影（需 VirGL / Zink 等渲染器）。
- 动态资源管理（模组 / 整合包 / 材质 / 光影 / 存档）与主题定制。
- 更新检查与启动公告读取本团队仓库：
  - 更新源 `ETQWFD/KCL-Mobile` 的 `version_map.json`
  - 公告源 KCL2 官网 `announcement-mobile.json`

---

## 与桌面版的关系

KCL 系列共用同一官网、同一反馈渠道与同一责任声明：

- KCL2：Windows 桌面主力版（基于 Plain Craft Launcher 2，MIT）。
- KCL CE：Windows 桌面社区版（MIT）。
- KCL（本仓）：Android 手机版（基于 FCL，GPL-v3）。

---

## 反馈与交流

- 问题反馈：https://github.com/ETQWFD/KCL-Mobile/issues
- 邮件：2416444244@qq.com
- QQ 群：1121514619

---

## 责任声明

KCL 不运营 Minecraft，也不运营任何多人服务器。未成年人充值、服务器封禁、道具纠纷、支付退款或服务器管理规则，请联系对应服主、服务器管理组、支付渠道或 Microsoft / Mojang 官方支持，这些不属于启动器可处理范围。本项目按“现状”提供、不附带担保。

---

## 开源协议与署名

本项目遵循 **[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)**。应用借鉴和引用了 PojavLauncher 与 Boat 的部分源码，分发时须遵守 GPL-v3。上游 FCL-Team、HMCL 等原作者署名全部保留；KCL 的改名与改动归龙腾OS / ETC Team。Minecraft 及相关标识归 Microsoft / Mojang 所有。
