# SoulBot

Soul（社交 App）上的 Android 自动聊天助手。基于无障碍服务自动识别界面、读取消息，调用大模型生成回复并自动发送；空闲时去广场浏览帖子并主动私信打招呼，全程模拟真人节奏（随机思考延迟、短句回复、口语化语气）。

> 本项目为个人自动化工具，使用时请遵守目标平台的服务条款与当地法律法规，并自行评估合规风险。

---

## 功能特性

- **自动回复未读消息**：扫描会话列表的未读角标 → 进入会话 → 读取最近聊天记录 → 大模型生成回复 → 自动发送
- **回复节奏模拟真人**：根据消息发送时间决定是否"思考"（刚收到的消息在可配置的随机秒数后回复，已发超过 1 分钟的消息立即回）；单条回复按句子拆分，最多分 3 条发送
- **广场主动私信**：空闲时进入广场浏览帖子，根据帖子内容生成自然开场白，点头像进主页 → 点私聊 → 发送
- **私信去重**：同一用户相同帖子内容永不重复打扰；内容变化后至少间隔可配置小时数才再次私信
- **处理奇遇铃弹窗**：读取对方的签名、匹配理由、引力签，生成破冰开场白并发送
- **处理新招呼**：自动打开"收到的新招呼"列表并逐一回复
- **自动跳过礼物弹窗**：遇到要求送礼的界面自动关闭返回
- **悬浮控制按钮**：常驻悬浮"开始/停止"按钮（可拖动），旁边显示当前动作与倒计时
- **界面状态机与防卡死**：自动识别聊天 / 会话列表 / 广场 / 个人主页 / 礼物弹窗 / 招呼列表 / 奇遇铃弹窗等界面，导航带校验；连续 8 次未变化自动休息 5 秒
- **多模型支持**：内置 DeepSeek、智谱 GLM、Kimi、通义千问、OpenAI，以及自定义中转站（OpenAI 兼容接口）
- **互动历史记录**：本地 SQLite 记录每次互动的对方昵称、对方消息、我方回复与时间，可在 App 内查看

---

## 工作原理

```
启动悬浮按钮（前台服务）
      │
      └─ 点击「开始」→ 无障碍服务后台线程运行主循环
              │
              ┌─ 奇遇铃弹窗？ → 生成破冰开场白 → 发送
              │
              ├─ 会话列表 → 有新招呼？ → 打开招呼列表逐一回复
              │           → 有未读红点？ → 扫未读 → 进会话读消息 → LLM 生成回复 → 发送
              │           → 空闲超时？   → 去广场刷帖子 → 随机私信一位 → 返回列表
              │
              ├─ 广场 → 浏览帖子 → 点头像 → 主页点私聊 → 根据帖子生成开场白 → 发送
              │
              └─ 其他界面 → 按返回键回到会话列表
```

关键点：

- **界面识别**：通过无障碍节点按 Soul App 的控件 id（`cn.soulapp.android:id/...`）定位输入框、发送按钮、未读角标、帖子卡片等
- **发送流程**：点击输入框 → 无障碍 `ACTION_SET_TEXT` 填入文本 → 等待发送按钮出现（最多 4 秒）→ 点击发送；多条消息之间随机停顿 2~5 秒
- **消息方向判断**：根据聊天气泡内头像相对屏幕中线的位置判断消息是"对方发的"还是"自己发的"
- **LLM 调用**：OpenAI 兼容 `POST /chat/completions`，`Authorization: Bearer <apiKey>`；兼容 `choices[].message.content`、`choices[].text`、`reasoning_content` 等返回格式，并自动去除 `<think>...</think>` 思维链标记
- **系统提示词**：内置一套"25 岁普通男生、随性幽默、口语化短句、用真 emoji、不暴露 AI"的聊天人设，并注入当前真实日期

---

## 环境要求

| 项 | 要求 |
|---|---|
| Android 系统 | 8.0（API 26）及以上 |
| 编译 SDK | 35（Android 15） |
| JDK | 17 |
| 依赖 | androidx（core/appcompat/material）、OkHttp 4.12.0 |
| 目标设备 | 需已安装 Soul App，并保持登录 |

---

## 构建

```bash
# 在项目根目录执行
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或使用 Android Studio 打开项目直接运行。

---

## 使用说明

首次使用按以下步骤配置：

1. **安装并登录 Soul App**，确保聊天、广场等功能可正常使用
2. **安装并打开 SoulBot**
3. **选择模型并填写 API Key**（设置页 ①② 区域）
   - 内置模型：直接选模型名，粘贴对应厂商的 API Key
   - 中转站：选择"中转站"，填写中转站地址（如 `https://xxx.com/v1`）和模型名
4. **开启权限**（设置页 ③ 区域）
   - 点击「开启无障碍服务」，在系统设置中打开 **SoulBot 自动助手**（App 通过无障碍服务读取界面并执行点击/滑动/输入）
   - 点击「开启悬浮窗权限」，授权悬浮窗（用于显示控制按钮）
5. **按需调整时间间隔**（设置页 ④ 区域），然后点「保存时间设置」
6. 点击「启动悬浮按钮」，屏幕右侧出现悬浮按钮，点击「开始」即开始自动运行；再点一次「停止」

---

## 配置项说明

| 配置项 | 默认值 | 含义 |
|---|---|---|
| 模型 / API Key | DeepSeek | 选择用于生成回复的模型并填写其 Key；Key 仅存本机（SharedPreferences） |
| 中转站地址 + 模型名 | — | 使用自定义 OpenAI 兼容接口时填写 |
| 广场私信间隔 | 240 秒 | 聊天区无未读时，每隔这么久去广场刷一次并私信一个人 |
| 回复延迟范围 | 5~15 秒 | 收到对方消息后，在这个范围内随机等待再回复（模拟思考，避免秒回） |
| 打招呼去重间隔 | 24 小时 | 同一个人发了新帖子后，至少隔这么久才再次私信（内容没变则永远不再私信） |

---

## 项目结构

```
android-app/
├── settings.gradle / build.gradle / gradle.properties
├── local.properties              # 本机 SDK 路径（不入库）
└── app/
    ├── build.gradle               # 应用配置：compileSdk 35 / minSdk 26 / targetSdk 35
    └── src/main/
        ├── AndroidManifest.xml    # 权限、MainActivity、无障碍服务、悬浮窗前台服务
        ├── java/com/soulbot/app/
        │   ├── MainActivity.kt          # 设置页：模型 / API Key / 权限状态 / 时间间隔
        │   ├── HistoryActivity.kt       # 互动记录列表页
        │   ├── SoulBotService.kt        # 无障碍服务核心：界面识别、主循环、回复与私信逻辑
        │   ├── FloatingButtonService.kt # 悬浮"开始/停止"按钮 + 状态面板（前台服务）
        │   ├── ModelClient.kt           # OkHttp 调用 OpenAI 兼容接口，解析回复
        │   ├── ModelConfig.kt           # 内置模型列表（DeepSeek/GLM/Kimi/千问/OpenAI/中转站）
        │   ├── Settings.kt              # SharedPreferences 读写（模型、Key、时间参数）
        │   ├── GreetDatabase.kt         # SQLite：打招呼去重（greet.db）
        │   └── HistoryDatabase.kt       # SQLite：互动历史（history.db）
        └── res/
            ├── layout/                  # activity_main / activity_history / item_history
            ├── values/                  # colors / strings / themes
            └── xml/accessibility_service_config.xml  # 无障碍服务配置
```

### 数据存储

| 数据库 | 表 | 用途 |
|---|---|---|
| `greet.db` | `greeted(name, post_text, time)` | 记录对谁、基于哪条帖子打过招呼，用于去重 |
| `history.db` | `history(name, their_msg, my_msg, time)` | 互动记录，供历史页展示 |

API Key 与设置存于 SharedPreferences（`soulbot_prefs`），卸载或清除数据后丢失。

---

## 已知限制

1. **界面布局适配**：消息方向阈值和滑动轨迹已按当前屏幕宽高动态计算，但分屏、横屏、折叠屏和平板上的 Soul 布局可能与手机竖屏不同，仍需真机验证。
2. **依赖 Soul 控件 id**：界面识别依赖 `cn.soulapp.android:id/...` 控件 id，Soul App 更新改版后可能失效，需同步更新常量。
3. **权限依赖**：必须手动开启无障碍服务与悬浮窗权限；部分厂商系统（如 MIUI/EMUI）对无障碍/自启动有额外限制。
4. **API Key 本机明文存储**：Key 以明文存在 App 私有 SharedPreferences 中，请勿在不可信设备上使用。
5. **风控与合规**：自动化发消息存在被目标平台风控、封号的风险，请自行评估后使用。
