# 应用更新与服务器接入

设置页的“应用更新”支持手动检查、版本提示、更新说明和浏览器下载。网络请求在后台执行；失败可重试。不会自动下载或安装，也不会在启动时自动访问服务器。

## 默认 GitHub 更新源

`app/src/main/res/values/strings.xml` 的 `update_manifest_url` 留空时，从公开的 `lujinxin/NeXtep` Releases 获取更新，无需令牌。

- 查询最近最多 100 条发布记录，包括 GitHub 标记的 Pre-release，排除草稿和没有已上传 APK 的发布。
- 标签使用 `vX.Y.Z` 或 `X.Y.Z`，按数字比较版本；不支持带 `-beta`、`-rc` 的标签。当前版本名也须使用 `X.Y.Z`。
- 只有一个 APK 时直接交给浏览器下载；多个 APK 时打开对应发布页供用户选择。
- GitHub 请求失败时不会误报“已是最新版本”；下载页按钮仍然可用。

GitHub API 定义参考：[Releases 官方文档](https://docs.github.com/en/rest/releases/releases)。

## 云服务器：静态文件即可

准备一个可公开访问、证书有效的 HTTPS 域名。把 APK 和 `update.json` 放在 Web 服务器的静态目录中，不需要数据库、后台进程或登录接口。更新 JSON 与 APK 可以放在不同域名，APK 也可以继续使用 GitHub 下载链接。

例如，网站根目录下安排：

```text
nextep/
  update.json
  NeXtep-0.4.0.apk
```

`update.json` 示例（示例版本不是已发布版本，域名需要替换）：

```json
{
  "versionCode": 4,
  "versionName": "0.4.0",
  "releaseNotes": "本版本更新说明\n第二条更新内容",
  "downloadUrl": "https://downloads.example.com/nextep/NeXtep-0.4.0.apk"
}
```

将应用资源中的配置替换为实际公开地址，然后由维护者编译发布：

```xml
<string name="update_manifest_url" translatable="false">https://downloads.example.com/nextep/update.json</string>
```

自建更新源根据 `versionCode` 判断更新。它必须与 APK 的 `app/build.gradle.kts` 配置一致，并在每次发布时递增；`versionName` 仅用于显示。四个字段中只有 `releaseNotes` 可以省略。地址必须是 HTTPS，JSON 不应超过 2 MiB。

配置自建源后，检查失败会提示重试，不会静默切换 GitHub。浏览器下载按钮在找到新版之前默认打开 GitHub 发布页，找到新版后使用 JSON 中的下载地址。

### Nginx 静态目录配置示例

在已配置域名与 HTTPS 证书的现有 `server` 块内添加以下 location。示例对应 `/var/www/nextep/update.json` 与 `/var/www/nextep/NeXtep-0.4.0.apk`，需要按服务器目录调整：

```nginx
location = /nextep/update.json {
    root /var/www;
    default_type application/json;
    add_header Cache-Control "no-cache" always;
    try_files $uri =404;
}

location /nextep/ {
    root /var/www;
    autoindex off;
    try_files $uri =404;
}
```

这只是供服务器维护者合并的配置片段，尚未部署或验证；不要覆盖现有站点配置。也可以使用已有的静态托管服务。原生应用请求 JSON 不需要 CORS 配置。

## 每次发布

1. 递增 APK 的 `versionCode` 并设置 `versionName`，使用原签名密钥签署 APK，以便用户覆盖安装。
2. 先上传采用版本化文件名的 APK，确认公开 HTTPS 链接可用。
3. 更新 JSON 中的版本、说明和下载地址；建议先上传临时 JSON，再原子替换 `update.json`，避免用户读取半个文件。
4. 如果有 CDN，刷新更新 JSON 的缓存。旧 APK 文件可以保留供回退使用。
5. 用户在设置页点击“检查更新”，查看说明并跳转浏览器，下载后手动安装，按发布说明重启设备。

目前已经安装的旧版应用需要先手动升级一次到包含此功能的版本，之后才能从应用内检查更新。

## 人工验收清单

本次未执行编译、测试或设备运行验证。由维护者检查：新版、同版、服务端版本较旧；断网、超时、GitHub 限流、无效 JSON/下载 URL；单 APK/多 APK；关闭或旋转设置页时请求完成；无浏览器；云服务器的 APK 覆盖安装。发现新版后应显示说明，取消弹窗后仍可使用下载按钮。
