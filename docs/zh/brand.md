# Nanodot 品牌

Nanodot 基于 [nanoMuse](https://github.com/nano-muse/nanoMuse) 及其
[OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis) 手机端子树。产品名为
**Nanodot**，默认助手叫 **Dot**。公开仓库为
[Mola-maker/nanodot](https://github.com/Mola-maker/nanodot)。P0 契约不声称未来的新悬浮球
或关怀模式已经实现。

## 标志 {#the-mark}

Dot 是 Nanodot 原创矢量素材：平静的圆形脸、两只眼睛与小表情。五态离线静态素材为待命、
干活中、等你回复、搞定与出了点问题。同一角色用于应用标志和默认头像。默认头像使用静态素材
与轻量状态反馈，旧的内置小龙视频不再作为默认动画。用户自定义头像与动画继续支持。

- 统一契约：`config/brand.nanodot.json`，由 `scripts/brand_profile.py` 校验。
- 矢量源文件：`assets/brand/nanodot-mark.svg`、`nanodot-icon.svg` 与
  `nanodot-avatar/{idle,working,waiting,happy,error}.svg`。
- 生成脚本：`scripts/gen-nanodot-assets.py`、`scripts/gen-android-icons.py` 与
  `scripts/gen-ios-icons.py`，使用同一个 `--brand-config` 参数。
- Dot 原创素材使用 GPL-3.0-or-later。SVG 注释记录来源；仓库的
  [LICENSE](../../LICENSE)、[NOTICE](../../NOTICE) 与
  [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) 保留上游条款和署名。

部分生成素材保留 `dragon-idle.webp`、`nm_avatar_idle.webp` 等内部文件名，以保持资源和客户端
接口兼容；文件内容是 Dot 静态素材。历史演示素材和上游标志不是 Nanodot 的默认素材。

## 颜色 {#colour}

以下值来自统一配置的 `themeColors`。默认主题跟随系统，已有明确主题选择优先。

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `background` | `#F4F7F8` | `#10191D` | 页面背景 |
| `surface` | `#FFFFFF` | `#182329` | 卡片与输入框 |
| `text` | `#16252C` | `#E5EDF0` | 文字 |
| `primary` | `#136F63` | `#7DD6C5` | 操作与链接 |
| `onPrimary` | `#FFFFFF` | `#10191D` | 主色上的文字 |
| `care` | `#7057A3` | `#C7B4F7` | 第三强调色，预留给后续关怀交互 |
| `outline` | `#687980` | `#8EABB5` | 边框与次要文字 |

## Android 映射 {#android-mapping}

Android 外部 applicationId 为 `io.github.nanodot.app`，系统链接 scheme 为 `nanodot://`。
Gradle namespace 和已有内部 Kotlin 包保留 `com.openminis.app` 与 `io.github.nanomuse.*`。
内部 `minis://` 链接、CLI 名称和沙箱路径保持。iOS 和桌面构建身份已同步为 Nanodot；
原生打包仍须在各平台验证。

自适应图标保留已有 activity 别名与资源名。浅色、深色皮肤使用配置中的 surface/background
与 primary。通知小图标是 24 dp 的 Dot 单色轮廓；Android 主题图标使用眼睛镂空的遮罩。

## 默认值与用户选择 {#defaults-and-user-choices}

仅初始化缺失字段。已有名字、SOUL.md、头像、动画、模型、主题与偏好保留。恢复默认是用户
主动操作。`showOnAccessibilityConnected=true` 记录未来悬浮球的选择，不启用旧状态胶囊、
不授予权限，也不打开 Hands。`continuousVoice=false` 记录安全的语音默认值。

P0 自动更新关闭（`enabled=false`、`repositoryUrl=null`）。Nanodot 发布页是手动查看的地址，
不代表已经有发布包。

## 署名与服务身份 {#attribution-and-service-identity}

源码署名、许可、引用和历史记录保留 nanoMuse、OpenMinis 与第三方项目的真实名称。
**nanoMuse Cloud** 仍是上游真实服务，其端点、隐私政策和模型提供方身份不属于 Nanodot。
Nanodot 不代该服务承诺免费额度，也不声称非营利身份。不要把 OpenMinis 或 Meta Muse 标志
当作 Nanodot 自己的标志。

[构建指南](build.md) 说明生成、签名与仍待完成的设备验收。
