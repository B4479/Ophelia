# 声音包格式（Voice Pack Format）

App 通过「一个目录即一个音色」的方式加载 GPT-SoVITS 模型。本文档定义该目录的结构，任何能产出同样结构的工具都可以接入。

## 目录结构

```
<voice>/
├── meta.properties                       必需，格式见下
├── t2s_encoder_fp32.onnx                必需
├── t2s_encoder_fp32.bin                  可选，external data（部分导出会生成）
├── t2s_first_stage_decoder_fp32.onnx     必需
├── t2s_stage_decoder_fp32.onnx           必需
├── vits_fp32.onnx                        必需
├── vits_fp32.bin                         可选，external data
├── prompt_encoder_fp32.onnx              可选，v2ProPlus 导出时存在（当前引擎不加载）
├── prompt_encoder_fp16.bin               可选，同上
└── ref/
    ├── ref_seq.i64                       int64    [ref_seq_len]
    ├── ref_bert.f32                      float32  [ref_seq_len × ref_bert_dim]
    ├── ssl.f32                           float32  [1 × 768 × ssl_len]
    ├── ref_audio.f32                     float32  [ref_audio_len]   （type=v2）
    └── ge.f32, ge_adv.f32                float32  [...]             （type=v2pp）
```

- `type=v2`：使用 `ref/ref_audio.f32`
- `type=v2pp`：使用 `ref/ge.f32` + `ref/ge_adv.f32`（v2ProPlus）
- `ref_bert_dim` 目前固定 1024；`ssl` 第二维固定 768

## meta.properties

UTF-8 文本，`key=value` 一行一条，`#` 开头为注释。

| 字段 | 必需 | 说明 |
|---|---|---|
| `name` | ✅ | App 中显示的音色名（可为中文） |
| `type` | ✅ | `v2` 或 `v2pp` |
| `sample_rate` | ✅ | 固定 32000 |
| `text_lang` | ✅ | 合成文本语言（`zh`） |
| `ref_lang` | ✅ | 参考音频语言（`zh`/`ja`/`en`/`ko`） |
| `ref_text` | ✅ | 参考音频对应的原文，必须与音频一致 |
| `ref_seq_len` | ✅ | `ref_seq.i64` 的元素个数 |
| `ref_bert_dim` | ✅ | 1024 |
| `ssl_len` | ✅ | `ssl.f32` 的第三维 |
| `ssl_shape` | 推荐 | 如 `[1, 768, 224]`，便于人工核对 |
| `ref_audio_len` | v2 必需 | `ref_audio.f32` 的元素个数 |

## 尺寸自洽关系（`tools/verify_pack.py` 会检查）

```
size(ref_seq.i64)   == ref_seq_len * 8
size(ref_bert.f32)  == ref_seq_len * ref_bert_dim * 4
size(ssl.f32)       == 768 * ssl_len * 4
size(ref_audio.f32) == ref_audio_len * 4
```

## 导入方式

把 `<voice>/` 整个目录压成 zip（任意层级含 `meta.properties` 即可）：

```
zip -r 音色名.zip 音色名/
```

App → 设置 → 音色 → 导入声音包 → 选 zip。

## 参考特征为什么要预计算

原始 GPT-SoVITS 推理链需要 HuBERT、speaker encoder、RoBERTa（合计约 1GB）。这些**只与参考音频有关、与用户输入无关**，因此在 PC 端一次性算好并固化成 `ref/*.f32`，端侧只保留 4 个推理图，体积与内存都降到手机可用范围。

> 换句话说：`ref/` 是「参考音频的指纹」，不是模型权重。换参考音频必须重新生成。

## 兼容性与限制

| 项 | 说明 |
|---|---|
| 模型版本 | 仅 GPT-SoVITS **v2 / v2ProPlus**；v1/v3/v4 不可用 |
| 单包体积 | int8 量化后约 320–450 MB |
| 运行时 | 任意支持 ONNX Runtime 的平台皆可，不限于 Android |
| 版权 | 音色权重归训练者/角色版权方，分发前请确认授权 |
