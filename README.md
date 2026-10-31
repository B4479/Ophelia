# Ophelia

本地优先的 Android AI 知识库：资料不出手机，回答带页码引用，语音也能离线。

## 功能

- **知识库**：PDF / EPUB / DOCX / PPTX / XLSX / TXT / MD / 图片导入，扫描件自动 OCR，后台断点续传
- **检索**：本地 bge-small 向量 + BM25 混合检索，回答里的 `[1]` 可跳到 PDF 原页
- **对话**：本地 GGUF（Qwen / GLM / DeepSeek / Gemma）或任意 OpenAI 兼容云端接口，流式输出
- **模板自适应**：读取 GGUF 自带 `chat_template`（Qwen / GLM / Gemma / Llama3），并清洗残留控制标记
- **工作任务**：引用多个知识库 + 工作助理模板，产出结构化结果
- **识图**：本地 Qwen2-VL-2B 或云端视觉模型
- **语音**：GPT-SoVITS 音色包朗读（可跳过动作描写）、情境声
- **长会话**：自动摘要（保留最近 12 条，更早压缩成记要）+ 长记忆语义召回（Top4）
- **生成参数**：温度 / 长度 / TopK / 重复惩罚，支持会话级覆盖与 SillyTavern 预设导入
- **世界书**：常驻 / 关键词 / 向量三种触发，支持 SillyTavern World Info JSON 导入
- **外观**：三套配色 × 明暗、跟随系统、每会话聊天背景、角色卡

## 构建

需要 JDK 17、Android SDK 35、NDK 26.3、Gradle 8.7。

打包后建议再做一次 16 KB 对齐（签名会失效，需重签）：

```bash
python tools/align16k.py --in app-release.apk --out aligned.apk   --keystore <ks.jks> --alias <alias> --storepass <pwd>
```

```bash
git clone https://github.com/B4479/Ophelia && cd Ophelia
git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp   # 体积原因不入库
echo "sdk.dir=/path/to/Android/sdk" > local.properties
gradle assembleDebug
```

## 模型与音色

- 对话 / 识图模型：应用内「模型选择」下载（GGUF），或推入 `files/models/`
- 音色包：设置 → 音色 → 导入 zip；制作方法与格式见 [docs/VOICE_PACK_FORMAT.md](docs/VOICE_PACK_FORMAT.md)，工具在 `tools/`

## 限制

- 仅 Android 10+ / arm64-v8a
- 本地模型实测范围 0.6B–2B；识图约 10–15 秒/张
- 无自动化测试，仅在小米 17 Pro Max（Android 15）真机验证
- 仓库不含模型权重、音色包、角色素材与 API Key

## 兼容格式

兼容 SillyTavern（酒馆）的世界书 `World Info` JSON、角色卡内嵌 `character_book` 与生成预设 JSON——仅做格式互通，不含其代码。

## 许可与致谢

代码 MIT（[LICENSE](LICENSE)）；第三方组件与上游项目见 [THIRD_PARTY.md](THIRD_PARTY.md)。

## 交流反馈

QQ：3685683097
