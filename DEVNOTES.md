# 開発メモ（作業の引き継ぎ用）

## 環境
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`（ビルド時は JAVA_HOME をここに）
- ビルド: `./gradlew build --no-daemon`
- Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.162.0+26.3 / Loom 1.18 / Mojang公式名（難読化なし）
- Ollama 0.40.2、モデル `qwen3.5:4b`（3.1GB、GPUに全部載る、返事は約1.6秒）。`think:false` を付けること
- MCの逆コンパイル済みソース：scratchpad の `mcsrc/common`, `mcsrc/client`（`./gradlew genSources` で作り直せる）
- ゲームテスト：サーバー版は eula=true が必要で、利用規約の同意にあたるのでユーザーの確認待ち。クライアント版ゲームテストを使う

## 設計（設計.md の要点）
- AIは計画（命令の並び）だけ。実行はプログラム
- 決まった言い方は AI を通さない（QuickCommands）
- 命令: follow / stop / move_to / collect / craft / build / store / say / remember（farm/guard/take は未実装）
- 実行中に材料・道具が足りなければ、タスクが「先にやること」を返して実行側が前に差し込む（深さ2まで）
- プログラムで直せない失敗はAIに1回だけ聞き直す。同じ失敗3回で諦めて報告

## パッケージ
- core: MCに依存しない純粋なロジック（JUnitでテスト）
- ai: LLMクライアント（Ollama / Anthropic / OpenAI互換）、設定、使用量ログ、呼び出し回数の制限
- entity / task / world / command: MCに依存する部分
