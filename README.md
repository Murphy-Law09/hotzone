# 热区响 (HotZone)

屏幕上的**几乎不可见的热区**，点一下就出声 —— 浮在任意应用之上。

## 它是什么

一个 Android 原生应用，用**悬浮窗**实现：

- 热区就是一块盖在屏幕上的透明窗口（默认 6% 不透明度，肉眼几乎看不出）
- 触摸直接落在热区上 —— 不需要无障碍服务，也不偷看别的应用
- 运行在**前台服务**里，切到微信、桌面都能用
- 点热点出声，连点音调升高，长按叠层连发

## 为什么不用网页

浏览器沙箱决定了网页只能接收自己窗口里的点击，无法覆盖到其他应用。
而 Android 从安全角度也不允许普通应用读取其他应用的触摸坐标，
唯一干净的办法就是"自己放一块窗口在上面"。

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/sound.mp3               ← 音效，替换成你的即可
├── java/com/dsh/hotzone/
│   ├── MainActivity.kt            主界面：权限、启停、编辑
│   ├── OverlayService.kt          前台服务 + 悬浮窗管理
│   ├── ZoneView.kt                热区视图（透明/描边两种状态）
│   ├── Zone.kt                    热区数据（屏幕比例坐标）
│   ├── ZoneStore.kt               配置持久化
│   ├── SoundPlayer.kt             SoundPool 封装
│   └── PitchTracker.kt            点击速度→音高、长按爬升
└── res/                           界面、图标、文案
.github/workflows/build.yml       云端编译
upload.js                          推送到 GitHub 用
```

## 手势行为

| 操作 | 效果 |
|---|---|
| 点一下 | 完整播一遍音效 |
| 连点 | 音调逐次升高（0.3 平滑，封顶 1.85 倍） |
| 按住 | 每 140ms 叠一层，音调一路爬升到 2.0 倍 |
| 松手 | 所有层立即停止 |

## 本地构建（有电脑时）

```bash
gradle assembleDebug
# 或
./gradlew assembleDebug
```

需要 JDK 17、Android SDK 34。手机上编不了（SDK 的 aapt2 只有 x86_64 版本）。

## 参数在哪调

`app/src/main/java/com/dsh/hotzone/OverlayService.kt` 顶部：

```kotlin
private const val HOLD_DELAY_MS = 200L      // 按住多久进入连发
private const val BURST_INTERVAL_MS = 140L  // 叠层间隔
private const val BURST_VOLUME = 0.5f       // 每层音量
private const val HOLD_MAX_RATE = 2.0f      // 长按最高音调
private const val HOLD_TC_SEC = 1.1f        // 爬升速度
```

热区默认透明度在 `Zone.kt` 的 `opacity = 0.06f`。
