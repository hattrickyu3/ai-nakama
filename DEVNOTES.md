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
- 言葉を受け取る順：教える言い方（TeachParser）→ 決まった言い方（QuickCommands）→ 覚えた技（SkillBook）→ いくつか続けた指示 → AI
- 命令: follow / stop / move_to / collect / craft / build / place / give / store / skill / say / remember（farm/guard/take/smelt は未実装）
- 実行中に材料・道具が足りなければ、タスクが「先にやること」を返して実行側が前に差し込む（深さ3まで、同じ命令のやり直しは2回まで）
- プログラムで直せない失敗はAIに立て直しを頼む（1回の話しかけにつき2回まで）
- 呼びすぎの防止：考えている間は次を受け付けない／立て直し2回まで／1日の上限。呼ぶ間隔の制限は使っていない

## パッケージ
- core: MCに依存しない純粋なロジック（JUnitでテスト）
- ai: LLMクライアント（Ollama / Anthropic / OpenAI互換）、設定、使用量ログ
- entity: NakamaEntity（体）、NakamaMind（考える）、TriggerWatch（きっかけ）
- task: TaskRunner と各タスク、共通部品（Walker 歩く・Breaker 壊す・Digger 掘り下げる・Scaffold 足場）
- world: 地形（Terrain）、場所（Places）、整地の場所選び（SitePlanner）、置く（Placer）、レシピ読み込み、建物の記録（BuildRecorder）
- Library: 覚えた技・設計図のファイル保存（config/ainakama/）

## はまったところ（次に直すときのために）
- シェルの heredoc で python に渡した文字列の `\n` は、本物の改行になってしまう。`
` を含む置き換えは Edit ツールで行う
- python で書き込むときは write_bytes を使う（write_text だと行末が CRLF になる）
- テスト用の木の葉っぱは DISTANCE=1 で置く。初期状態（7）だと時間がたつと消え、「自然の木」と判定されなくなる
- 仲間が「近づく処理」で毎tick navigation.stop() を呼ぶと、よける移動が打ち消される（BuildTask の evadeTicks）
- 扉を開けられないと、自分で建てた小屋に閉じ込められる（setCanOpenDoors + OpenDoorGoal）
- レシピの材料は「オークの板材」「板材ならどれでも」のようにキーが重なる。取り置きは実際のIDごとに数える（CraftPlanner.State.reserve）
- 小さいAI（4B）は「できないこと」を強く書くと慎重になりすぎる。説明文を変えたら LiveModelEval で必ず確かめる

## 状況（2026-10-10 夕方）
- 単体テスト 50件すべて成功（`./gradlew build`）
- ゲーム内テスト（`./gradlew runClientGameTest`、約3分）すべて成功：原木集め、ツルハシ・はしごのクラフト（本体レシピ997種）、掘り下げて丸石、整地して小屋（板材71・扉・床下の穴なし）、置く、渡す、建物を見せて覚える→別の場所に建てる、覚えた技、決まり、言葉で教える、AI経由の指示
- 本物のAIでの評価（LiveModelEval）：qwen3.5:4b で12/12、1回あたり入力約800・出力約50トークン、約1秒

## 次にやること（候補）
- 普段のランチャーで遊べるように、Fabric を入れて jar を mods に置く手順を整える
- 畑仕事・戦う・チェストから取り出す・かまどで焼く
- 覚えた設計図の向きを変えて建てる（今は見せたときと同じ向き）
- 知識を仲間どうしで共有する
- 1時間遊んで、呼び出し回数と量を測る
