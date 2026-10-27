# Ophelia · 本地优先的中文 AI 知识库

> 一个跑在 Android 手机上的本地 AI 知识库 + 角色对话应用：资料不出手机，模型可离线，回答带页码引用。

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white">
  <img alt="abi" src="https://img.shields.io/badge/ABI-arm64--v8-555">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white">
  <img alt="compose" src="https://img.shields.io/badge/Compose-Material3-4285F4">
  <img alt="llm" src="https://img.shields.io/badge/llama.cpp-GGUF-000">
  <img alt="embed" src="https://img.shields.io/badge/ONNX%20Runtime-bge--small--zh-005CED">
</p>

**Ophelia** 把「导入资料 → 本地向量化 → 混合检索 → 带引用的回答」整条链路放到手机端完成。没有服务器、没有账号体系，断网也能用；需要更强能力时可接任意 OpenAI 兼容云端接口。

---

## ✨ 核心特性

### 📚 知识库

- 支持 **PDF / EPUB / DOCX / PPTX / XLSX / TXT / MD / RTF / CSV / 图片**
- 扫描版 PDF 自动回退 **OCR**（ML Kit 中文识别）
- 后台导入 + **断点续传**（WorkManager），重启后自动续跑
- 分块 450 字 / 80 重叠 → **bge-small-zh-v1.5（512 维）** 向量化
- **混合检索**：0.65 × 稠密余弦 + 0.35 × BM25（中文二元组），每库独立 TopK
- 回答中的 `[1][2]` 可**点开跳到 PDF 原始页**

### 💬 双模式对话

| 模式 | 用途 |
|---|---|
| **对话** | 日常聊天、角色卡陪聊 |
| **工作任务** | 引用多个知识库 + 设定工作助理（输出格式/模板），产出结构化结果 |

- 流式输出（云端 SSE / 本地 token 回调），可随时停止并保留已生成内容
- 消息操作：朗读、复制、重新生成、切换模型、删除（含树形删除）、分支、修改
- 会话级音色覆盖、每会话聊天背景（模糊/暗化可调）
- 引用芯片、附件（图片走视觉模型，文档抽取注入）

### 🧠 模型中心

三个独立维度，各自选择、互不影响：

| 类别 | 本地（GGUF / ONNX） | 云端 |
|---|---|---|
| 对话 | Qwen2.5-1.5B、Qwen3-1.7B、Qwen3-0.6B、GLM-Edge-1.5B、DeepSeek-R1-Distill-1.5B、Gemma-3-1B | 任意 OpenAI 兼容（DeepSeek / Kimi / GLM / Qwen） |
| 识图 | Qwen2-VL-2B（llama.cpp mtmd） | Qwen3-VL-Plus、Qwen-VL-OCR |
| 向量 | bge-small-zh-v1.5（内置） | — |

- 下载支持**断点续传 + 字节数 / SHA-256 校验**
- 模型清单与下载源可在应用内管理（ModelScope / 自定义源）

### 🔊 语音

- 内置 **GPT-SoVITS（Genie ONNX）** 推理引擎，音色包即插即用
- 朗读自动跳过动作描写（`（）`/`()`/`【】`/`*…*`），可开关；保留年份、公式、说明性括号
- 情境声（环境音）循环播放，朗读时自动闪避

### 🎨 外观

- 三套配色 × 明暗两版 + 跟随系统
- 角色卡（PNG tEXt / JSON 导入）、个人主页身份、头像

---

## 🏗 技术架构

```
┌──────────────────────────── Compose UI ────────────────────────────┐
│ ChatScreen  MainActivity  SettingsScreen  ModelCenter  ThemeScreen │
└───────────────┬────────────────────────────────────┬───────────────┘
                │                                    │
        ┌───────▼────────┐                  ┌────────▼────────┐
        │  Db (SQLite)   │                  │  ModelPrefs     │
        │  11 张表 v14    │                  │  本地/云端分派    │
        └───────┬────────┘                  └────────┬────────┘
                │                                    │
   ┌────────────▼───────────┐            ┌───────────▼────────────┐
   │ Hybrid (dense+BM25)    │            │ Llm / LocalLlm         │
   │ Embedder (ONNX 512d)   │            │ Vision / LocalVision   │
   └────────────┬───────────┘            └───────────┬────────────┘
                │                                    │
   ┌────────────▼───────────┐            ┌───────────▼────────────┐
   │ Extractors + OCR       │            │ llama.cpp (JNI, mtmd)  │
   │ ImportWorker (WM)      │            │ GPT-SoVITS (ONNX)      │
   └────────────────────────┘            └────────────────────────┘
```

**技术栈**：Kotlin 1.9 · Jetpack Compose (Material3) · 原生 SQLiteOpenHelper · ONNX Runtime Android · llama.cpp（vendored，含 `tools/mtmd`）· pdfbox-android · ML Kit OCR · OkHttp · WorkManager

### 目录结构

```
app/src/main/
├── java/io/dsh/localkb/          # 应用层（UI / 数据 / 引擎 / 模型管理）
│   ├── ChatScreen.kt             # 会话、流式、附件、分支、背景、朗读
│   ├── MainActivity.kt           # 路由、知识库首页与详情
│   ├── Db.kt                     # 全部表结构与 CRUD（v14）
│   ├── Hybrid.kt / Ml.kt         # 混合检索 / 分词·嵌入·分块
│   ├── Engine.kt                 # PDF 解析、检索、云端 LLM（SSE）
│   ├── LocalLlm.kt               # 本地对话（JNI 封装）
│   ├── LocalVision.kt            # 本地识图（mtmd JNI）
│   ├── Catalog.kt                # 本地模型清单（含 SHA-256）
│   ├── ModelCenterScreen.kt      # 模型中心
│   ├── Tts.kt / Ambience.kt      # 声音包、流式朗读、情境声
│   └── Theme.kt / ThemeScreen.kt # 配色 token 与配色页
├── java/com/moe/gsvtts/          # GPT-SoVITS 端侧推理引擎
├── cpp/                          # llama_bridge / vision_bridge / CMake
└── assets/                       # bge-small 模型、分词表、TTS 文本资源
```

---

## 🚀 构建

### 环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk / targetSdk 35，buildTools 35.0.0 |
| NDK | 26.3.11579264 + CMake 3.22.1 |
| Gradle | 8.7 |

### 步骤

```bash
# 1. 克隆
git clone <this-repo> Ophelia && cd Ophelia

# 2. 放入 llama.cpp（体积原因不入库，需含 tools/mtmd）
git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

# 3. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 4. 构建
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 可选：签名 Release

在项目根目录创建 `keystore.properties`（**不入库**）：

```properties
storeFile=your-release.jks
storePassword=******
keyAlias=your-alias
keyPassword=******
```

然后 `gradle assembleRelease`。

---

## 📱 首次使用

1. **导入资料**：知识库 → 新建 → 导入文件 / 文件夹（自动后台解析）
2. **配置模型**（二选一）：
   - 本地：模型中心 → 对话 → 下载 Qwen2.5-1.5B 等（或推入 `files/models/*.gguf`）
   - 云端：模型中心 → 对话 → 添加云端模型（Base URL + Key + 模型别名）
3. **开始对话**：侧边栏「新对话」或「新工作任务」（后者可引用多个库并设定工作助理）
4. **音色**：设置 → 音色 → 导入声音包（zip，内含 `meta.properties` + onnx）

---

## 🔒 隐私与数据

- 所有资料、向量、聊天记录都在应用私有目录，不上传任何服务器
- 云端模型为**可选**，一旦配置即会把提问与检索片段发送到你指定的接口
- API Key 保存在本机 SQLite（**明文**，请勿在共享设备上配置）
- 音色包与角色卡版权归各自作者，请自行确认使用范围

---

## ⚠️ 已知限制

- 仅支持 **arm64-v8a**、Android 10（API 29）及以上
- 本地对话实测范围 0.6B–1.7B；本地识图约 10–15 秒/张（视机型）
- 导入格式不含 DOC(97-2003)、MOBI/AZW3、CHM、DjVu
- 无自动化测试，验证以真机手工回归为主
- 不支持多用户 / 数据加密 / 云端向量

---

## 🗺 Roadmap

- [ ] 更多本地模型（Qwen3-4B、MiniCPM4）
- [ ] 向量模型可切换（bge-m3，需重建索引）
- [ ] 下载源自动解析模型清单
- [ ] 知识库导出 / 备份
- [ ] 单元测试与 CI

---

## 📄 License

代码部分建议采用 MIT（请按你的意愿替换）。**第三方资源**遵循各自许可：

- Qwen / GLM / DeepSeek / Gemma 模型：见对应仓库许可
- llama.cpp：MIT
- bge-small-zh-v1.5：MIT
- GPT-SoVITS：MIT；**音色包**版权归训练者/角色版权方

本项目不包含任何 API Key、签名文件或第三方音色权重。
