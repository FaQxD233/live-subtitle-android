# AGENTS.md

本文件记录在本仓库内工作时必须遵守的项目约定。

## Git 工作流

- 所有提交直接提交到 `main` 分支。
- 不新建分支，不发起 Pull Request。

## API 事实备忘（实测，勿再踩坑）

- Live Translate 模型（`gemini-3.5-live-translate-preview`）的
  `generationConfig.responseModalities` **是摆设**（2026-10-19 用真实 API 实测）：
  `["TEXT"]` 会被正常接受（返回 `setupComplete`、无报错），但模型照样生成并持续
  推送 24kHz 音频（`modelTurn.parts[].inlineData`），且从不输出文本 part。
- 字幕文本来自 `outputAudioTranscription`（对模型**自己生成的语音**的转写），
  因此音频的云端生成与下行传输是拿到字幕的必要代价，客户端无法通过 modality
  规避。`playTranslation=false` 时 App 收到音频直接丢弃即可，**不要再尝试用
  TEXT-only 模态做省带宽/省算力优化**。
- `echoTargetLanguage` 的含义是「输入本来就是目标语言时是否复述」，与是否本地
  播放翻译无关（本地播放由 `playTranslation` 控制，B3 已拆分）。
- `inputTranscription` / `outputTranscription` 是**增量片段**（2026-10-20 用
  `live-api-demo/test_text_modality.py` 实测）：每条只带新增的文字并自带前导空格，
  例如 `'Hello. This is a'`、`' test of the live'`、`' translation'`，直接拼接即可。
  **不要再按「新片段以上一片段开头就替换」的累积式规则合并**，那样会吞掉恰好重复的
  片段（例如「谢」+「谢」）。
