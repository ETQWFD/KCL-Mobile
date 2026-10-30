/*
 * KCL Mobile
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl.upgrade

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases 最新版本接口（`GET /repos/{owner}/{repo}/releases/latest`）的响应模型。
 *
 * 只取我们关心的字段：tag 名和资产列表。启动器据此**动态**拿到安装包下载地址，
 * 无需在 `latest_version_md.json` 里写死 files 列表。
 */
@Serializable
data class GithubReleaseApi(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("assets")
    val assets: List<Asset>,
) {
    @Serializable
    data class Asset(
        @SerialName("name")
        val name: String,
        @SerialName("browser_download_url")
        val downloadUrl: String,
        @SerialName("size")
        val size: Long,
    )
}
