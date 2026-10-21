<div align="center">
    <img width="96" src="/FCL/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"></img>
</div>

<h1 align="center">KCL (Android)</h1>

<div align="center">

[![Android CI](https://github.com/ETQWFD/KCL-Mobile/actions/workflows/continuous.yml/badge.svg)](https://github.com/ETQWFD/KCL-Mobile/actions/workflows/continuous.yml)
[![Release](https://img.shields.io/github/v/release/ETQWFD/KCL-Mobile?style=flat-square&color=0b7a46)](https://github.com/ETQWFD/KCL-Mobile/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-0b7a46?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.html)

</div>

[简体中文](./README.md) | English

When the usual launchers become temporarily unavailable because of network, maintenance, or outside pressure, players still need a fallback entry point to their local game. **KCL** keeps version downloading, legitimate login, and local instance management available in that situation; this is the Android edition of the KCL family.

KCL (Android) is a redistribution of [Fold Craft Launcher (FCL)](https://github.com/FCL-Team/FoldCraftLauncher). FCL was built by [FCL-Team](https://github.com/FCL-Team) on top of [HMCL](https://github.com/HMCL-dev/HMCL) core functionality with a [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) backend. KCL is an independent third-party modification maintained by 龙腾OS / ETC Team; update checks and the in-launch announcement point to the ETQWFD repositories, not to upstream FCL-Team.

---

## Download

- Project site (shared with the desktop KCL2 / KCL CE builds): https://etqwfd.github.io/KCL2/
- Universal APK (all architectures, directly installable): https://github.com/ETQWFD/KCL-Mobile/releases
- Requires Android 8.0+ (minSdk 26); the Java runtime is fetched on first run as needed.

---

## Features

- Full Minecraft: Java Edition support (including the latest snapshots), version isolation and modpack import.
- Mod loaders: Forge / NeoForge / Fabric / Quilt / OptiFine / LiteLoader, etc.
- Built-in Java runtimes (8 / 17 / 21 / 25) plus custom runtime import.
- Virtual mouse, custom key mapping and control layouts; shaders (VirGL / Zink renderers).
- Dynamic asset management (mods / modpacks / resource packs / shaders / worlds) and theming.
- Update and announcement sources hosted by our team:
  - Update: `version_map.json` in `ETQWFD/KCL-Mobile`
  - Announcement: `announcement-mobile.json` on the KCL2 site

---

## Family

- KCL2 — Windows desktop main build (based on Plain Craft Launcher 2, MIT).
- KCL CE — Windows desktop community build (MIT).
- KCL (this repo) — Android build (based on FCL, GPL-v3).

---

## Feedback

- Issues: https://github.com/ETQWFD/KCL-Mobile/issues
- Email: 2416444244@qq.com
- QQ group: 1121514619

---

## Liability

KCL does not operate Minecraft or any multiplayer server. For underage charges, server bans, item disputes, refunds, or server rules, contact the server owner, the server administration team, the payment channel, or official Microsoft / Mojang support; these are outside a launcher's scope. This project is provided as-is without warranty.

---

## License and credits

Licensed under **[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)**. The app uses and quotes parts of PojavLauncher and Boat, so redistribution must follow GPL-v3. All upstream credits (FCL-Team, HMCL, and others) are retained; the KCL rename and changes belong to 龙腾OS / ETC Team. Minecraft and its marks belong to Microsoft / Mojang.
