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
- 実行中に材料・道具が足りなければ、タスクが「先にやること」を返して実行側が前に差し込む（深さ3まで、同じ命令のやり直しは2回まで）
- プログラムで直せない失敗はAIに立て直しを頼む（1回の話しかけにつき2回まで）。それ以上はプレイヤーに相談

## パッケージ
- core: MCに依存しない純粋なロジック（JUnitでテスト）
- ai: LLMクライアント（Ollama / Anthropic / OpenAI互換）、設定、使用量ログ、呼び出し回数の制限
- entity / task / world / command: MCに依存する部分

## 状況（2026-10-10 朝）
- 単体テスト 32件すべて成功（`./gradlew build`）
- ゲーム内テスト（`./gradlew runClientGameTest`）すべて成功：原木4本 166tick、ツルハシ 60tick、丸石3個 124tick、小屋71ブロック 約990tick、プレイヤーの原木の壁は切らない、「ついてきて」、AI経由の自由な指示
- 本物のAIでの評価（LiveModelEval）：qwen3.5:4b で12/12、1回あたり入力約600・出力約70トークン、約1秒
- コードレビュー（code-reviewer エージェント）の指摘は HIGH/MEDIUM をすべて修正済み

## 次にやること（候補）
- 普段のランチャーで遊べるように、Fabric を入れて jar を mods に置く手順を整える
- 小さいモデルの返事の日本語を良くする（system に例文を足す、gemma4:e4b と比べる）
- うまくいった計画の使い回し、畑仕事・見張り・取り出し
- 1時間遊んで、呼び出し回数と量を測る
