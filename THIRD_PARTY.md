# 第三方组件与许可

本项目在以下开源项目基础上实现。**除特别注明外均为 MIT / Apache-2.0 许可**，使用时请保留各自的许可声明。

## 推理与模型

| 组件 | 用途 | 许可 |
|---|---|---|
| [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | 本地对话与识图推理（`app/src/main/cpp`，含 `tools/mtmd`） | MIT |
| [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS) | `tools/` 的 GPT-SoVITS → ONNX 转换、参考特征预计算流程参考 | MIT |
| [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) | 语音模型结构与推理语义 | MIT |
| [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | 端侧推理运行时（`onnxruntime-android`） | MIT |
| [FlagOpen/FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding) · [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) | 知识库向量模型（`assets/bge-small-zh-v1.5.onnx`） | MIT |

## 文本前端（TTS）

| 组件 | 用途 | 许可 |
|---|---|---|
| [spider996/atri-vc](https://github.com/spider996/atri-vc) | 中文 G2P 实现参考（`com/moe/gsvtts/TextFrontend.kt`）与 `assets/tts/` 词表（symbols / charmap / poly / p2s / phrases） | 见其仓库 LICENSE（GitHub 未识别为标准 SPDX 标识） |

## 应用依赖

| 组件 | 用途 | 许可 |
|---|---|---|
| AndroidX / Jetpack Compose / Material3 | UI 与基础库 | Apache-2.0 |
| Kotlin / kotlinx-coroutines | 语言与协程 | Apache-2.0 |
| AndroidX WorkManager | 后台导入 | Apache-2.0 |
| Square [OkHttp](https://github.com/square/okhttp) | 云端接口与模型下载 | Apache-2.0 |
| [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | PDF 解析 | Apache-2.0 |
| Google ML Kit（text-recognition-chinese） | 扫描件 OCR | 见 [ML Kit 条款](https://developers.google.com/ml-kit/terms) |

## 不包含的内容

- **模型权重**：Qwen / GLM / DeepSeek / Gemma / Qwen2-VL / bge 等按各自仓库许可使用，不随本仓库分发
- **音色包**：`.pth` / `.ckpt` / 转换后的 ONNX 声音包均不在仓库内，版权归各自训练者/权利方
- **角色素材**：角色卡、立绘、语音样本的版权归原作品权利方
- **密钥与签名**：不含任何 API Key、`keystore`、`*.jks`

## 使用注意

1. 二次分发本项目请保留 `LICENSE` 与 `THIRD_PARTY.md`
2. 本项目仅供个人学习与技术研究；用于商业场景前请自行确认模型、音色与角色素材的授权
3. `tools/` 生成的音色包属于**声音克隆**产物，涉及真人或角色声音时需取得授权
