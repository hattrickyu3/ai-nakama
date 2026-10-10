# AI仲間Mod（ai-nakama）

マイクラ Java版 26.3（Fabric）に、言葉で指示できる村人の仲間「ミナ」を追加するMod。
**AIは計画（命令の並び）を立てるだけで、歩く・掘る・作る・建てるはプログラムが行う。** AIの利用料を抑えるための作りで、これがこのModの一番大事な方針。

## ユーザーについて

- 日本語で話す。コマンド操作には慣れていないので、手順は1つずつ具体的に伝える。専門用語は言い換えるか、短く説明を添える
- 遊び方の説明書と設計の記録は、Obsidianのノートにある（ユーザーはこちらを読む）
  - `C:\Users\hattr\OneDrive\大学\_授業以外\AI仲間Mod\使い方.md`
  - `C:\Users\hattr\OneDrive\大学\_授業以外\AI仲間Mod\設計.md`
  - できることを増やしたり変えたりしたら、`使い方.md` も直す。書き方の決まりは `C:\Users\hattr\OneDrive\大学\CLAUDE.md`（Mermaid・HTML・ノートの埋め込みは使わない）
- GitHub：`hattrickyu3/ai-nakama`（非公開）。**push は頼まれたときだけ**

## まず読むもの

- `DEVNOTES.md`：環境、パッケージの分け方、はまったところ、今の状況、次の候補
- `README.md`：遊び方と、できること・できないことの一覧

## コマンド

Git Bash で実行する。先に `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"` が必要。

| やること | コマンド | メモ |
|---|---|---|
| ビルドと単体テスト | `./gradlew build --no-daemon` | 1分ほど |
| ゲームを起動するテスト | `./gradlew runClientGameTest --no-daemon` | 約3〜6分。**マイクラの画面が開く**。10分で打ち切られるので、裏で動かして `build/gametest.log` を見る |
| 本物のAIでの確認 | `./gradlew test --no-daemon -Dainakama.live=true --tests '*LiveModelEval*'` | Ollama が動いていること。約30秒 |
| 遊ぶ | `start-game.bat` をダブルクリック（ユーザーが行う） | |

## 守ること

- **AIに頼る前に、プログラムで読めないかを考える。** 小さいAI（qwen3.5:4b）は、教わったことに気づけない・できないことを「やる」と言う・日によって返事が変わる。決まった言い方（`QuickCommands`）、教える言い方（`TeachParser`）、覚えた技（`SkillBook`）で受けられるものは、AIに回さない
- **AIへの説明文（`PromptBuilder`）を変えたら、必ず LiveModelEval で確かめる。** 少し変えただけで、ほかの頼みへの返事が変わる
- **プレイヤーの建物を壊さない。** 掘ったりどけたりしてよいのは `Terrain` が自然のものと判断したブロックだけ。新しい動きを足すときも、この判断を通す
- ゲームの中のことを変えたら、単体テストだけでなく、ゲームを起動するテストも通す。足した動きは、そのテスト（`src/gametest`）にも入れる
- ユーザーから「うまくいかなかった」と言われたら、まず記録を見る：`run/logs/latest.log`（チャットとミナの返事）、`run/logs/ainakama-usage.csv`（AIを呼んだ回数と量）
- サーバー版のゲームテスト（`eula = true`）は使わない。マイクラの利用規約への同意にあたるので、ユーザー本人にしか決められない
- テストで残った java のプロセスを止めるときは、`clientGameTest` を含むものだけにする。ユーザーが遊んでいるマイクラ（`runClient`）を巻き込まない
- APIキーをコードやリポジトリに入れない（設定は `config/ainakama.json` か環境変数 `AINAKAMA_API_KEY`）

## ファイルを書き換えるときの注意（Windows）

- python で書き込むときは `write_bytes` を使う（`write_text` だと行末が CRLF になる）
- シェルの heredoc で python に渡した文字列の中の「バックスラッシュ＋n」は、本物の改行になってしまう。Javaの文字列にそれを含む置き換えは、Edit ツールで行うか、python のファイルを Write ツールで作ってから実行する

## コミット

- `feat:` `fix:` `docs:` `test:` などで始め、本文は英語で何を・なぜ変えたかを書く（これまでの履歴に合わせる）
- 作業の区切りでこまめにコミットする
