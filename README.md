# GambleGate

Paperサーバーに、入退場管理・会場パス・演出・収支統計を備えたカジノを追加するプラグインです。複数のWorldEdit選択を組み合わせて複雑な会場を構成でき、スロット、ハイ＆ロー、クラッシュ、16マスの物理ルーレットを設置できます。

## 主な機能

- 複数の直方体リージョンを組み合わせた会場判定
- Vault経済による入場料、賭け金、パス料金、賞金の決済
- 入口・出口ゲート、退出猶予、不正侵入のロールバック
- 買い切り30日、30日自動更新、7日お試しの会場別パス
- VIP専用リージョンと権限連動の鉄扉
- スロット、ハイ＆ロー、クラッシュ、物理ルーレット
- プレイヤー別BGM、スコアボード、ゲーム説明看板
- 会場全体・プレイヤー別・日別の収支統計
- 純利益に応じた屋上花火とゲーム別の演出
- `config.yml`と`message.yml`による詳細なカスタマイズ

> [!IMPORTANT]
> 対応対象はPaper 1.21.11です。スロットはこのバージョンの棚ブロックを使用します。異なるMinecraft/Paperバージョンでの動作は保証していません。

> [!NOTE]
> Minecraft内の仮想通貨で遊ぶ用途を想定しています。現金・暗号資産・換金可能な資産を扱う場合は、運営地域の法令や各サービスの規約を必ず確認してください。

## 必要環境

| 種類 | バージョン | 用途 |
| --- | --- | --- |
| Java | 21 | 実行・ビルド |
| Paper | 1.21.11 | サーバーAPI |
| WorldEdit | 7.4.2 | 会場・VIPリージョンの選択 |
| Vault | 1.7系 | 経済API |
| Vault対応経済プラグイン | 任意 | 実際の残高管理 |
| GSit | 3.5.1 | ゲーム台の着席判定 |

上記依存プラグインはすべて必須です。Vaultだけでは残高を管理できないため、Vault対応の経済プラグインも別途導入してください。

## インストール

1. Paper 1.21.11サーバーへWorldEdit、Vault、経済プラグイン、GSitを導入します。
2. GambleGateのjarを`plugins/`へ配置します。
3. サーバーを起動します。
4. コンソールでGambleGateと依存プラグインが有効になったことを確認します。

初回起動時に`plugins/GambleGate/`へ設定ファイルとデータファイルが作成されます。一般的な`/reload`ではなく、設定変更後は`/gamblevenue reload`またはサーバー再起動を使ってください。

## 最短セットアップ

例として、入場料1,000円の`casino`会場を作ります。

```text
/gamblevenue create casino 1000
```

WorldEditで会場内を選択し、選択範囲を追加します。

```text
/gamblevenue addregion casino
```

複雑な形の会場は、WorldEditで別の範囲を選び直して同じコマンドを繰り返してください。登録した全リージョンの和集合が会場内になります。

次にゲートを作成します。

```text
/gamblevenue creategate casino main
```

入口側でプレイヤーが乗る1ブロックの上に立ち、入口を登録します。

```text
/gamblevenue setgateblock casino main entrance
```

会場内の出口ブロック上へ移動し、出口を登録します。

```text
/gamblevenue setgateblock casino main exit
```

`entrance`は出口から戻る位置、`exit`は入口から入る位置として相互に使われます。着地点と視線を細かく上書きする場合は、移動先で次を実行します。

```text
/gamblevenue setdestination casino main entrance
/gamblevenue setdestination casino main exit
```

ログアウトや強制退出後の移動先は、会場外の位置に立って登録します。

```text
/gamblevenue setlogoutdestination casino
```

## 会場システム

### 入退場と保護

入口ブロックへ乗ると入場確認GUIが開き、Vaultから入場料を支払うと会場内へ移動します。ゲート判定には縦方向の余裕があり、ジャンプしながら通過しても反応します。

出口では再入場に料金が必要になることを確認してから退出します。有効なパスを持つプレイヤーは入退場GUIを省略します。会場リージョン外の1ブロック分は既定で退出猶予となり、それより外へ徒歩またはテレポートで移動すると退出が確定します。

パスまたはバイパス権限を持たずに会場へ直接侵入したプレイヤーは、警告後に元の位置へ戻されます。会場周辺では、設定された半径内への敵対モブとエンダーマンの侵入・スポーン・テレポートも防止します。

### 会場パス

会場ごとに次のパスを販売できます。

| パス | 期間・更新 |
| --- | --- |
| 買い切り | 購入から現実時間で30日 |
| 自動更新 | 30日ごと。期限後の次回ログイン時にVaultから決済 |
| お試し | 7日間。プレイヤー・会場ごとに1回のみ |

パス自販機にするボタンを見ながら登録します。

```text
/gamblevenue addpassmachine casino
/gamblevenue setpassprice casino one-time 5000
/gamblevenue setpassprice casino subscription 4500
/gamblevenue setpassprice casino trial 500
```

自販機は会場へ正規入場中のプレイヤーだけが使用できます。有効なパスがある間は別のパスを購入できません。自動更新中は有効期限の確認と更新キャンセルができます。更新時に残高が不足している場合はパスが失効します。

OPはパスを完全破棄、または即時期限切れにできます。

```text
/gamblevenue pass revoke <プレイヤー> <会場ID>
/gamblevenue pass expire <プレイヤー> <会場ID>
```

パスが失効したプレイヤーは会場外へ移動します。ゲーム中の場合は結果と払戻しが確定した後に退出します。

### VIPエリア

WorldEditでVIP範囲を選択して登録します。

```text
/gamblevenue addvipregion casino
```

VIP入口の鉄扉を見ながら登録すると、権限を持つプレイヤーが近づいた時だけ自動で開きます。

```text
/gamblevenue addvipdoor casino
```

全会場のVIP権限は`gamblegate.vip`、会場別権限は`gamblegate.vip.<会場ID>`です。招待機能はありません。

### BGM・スコアボード・花火

- 正規入場中は、Vault残高、今回の収支、プレイ回数、パス期限などをスコアボードへ表示します。
- BGM設定用ボタンを見ながら`/gamblevenue addbgmbutton <会場ID>`を実行すると、プレイヤーごとに`ON → 小さめ → OFF`を切り替えられます。
- `plugins/GambleGate/casino-midi/`へ`.mid`または`.midi`を入れると、入場者本人にランダム再生します。
- 屋上で`/gamblevenue addfireworkpoint <会場ID>`を実行すると、プレイヤーが純利益を得た時に1〜10発の花火を打ち上げます。

MIDIは全トラック・全チャンネルのNOTE ONを読み込みます。チャンネル10のドラムはバスドラム、スネア、ハイハットへ簡略変換します。楽器変更、サステイン、ピッチベンド、音量オートメーションには対応していません。

## ゲーム

すべてのゲームは正規入場中だけ遊べます。基準RTPは既定で1.0です。動的確率補正を有効にすると、収支統計に応じて既定で98〜102%の範囲へ補正されます。

### スロット

棚ブロック、GSitで座れる椅子、レバーを近くに配置します。椅子へ座った状態で賭け金を指定すると、台IDが自動採番されます。

```text
/slot setup casino 500
```

椅子から設定半径内にある最も近い棚とレバーが自動連携されます。ブロックを置き直した場合も再検出されます。着席すると3枠が準備状態になり、レバーを引くと設定済みの賭け金で開始します。

通常結果は、全バラバラ、2つ一致、銅・鉄・エメラルド・ダイヤモンド・金の3つ揃いです。金3つでGold Rushへ入り、金が続くたびに無料スピンと賞金候補が連鎖します。Gold Rush中の銅3つは候補額を含めて没収されます。

### ハイ＆ロー

GSitの椅子の近くへ次の3ボタンを配置します。

- 歪んだ木のボタン: LOW
- 磨かれたブラックストーンのボタン: 開始・換金
- 真紅の木のボタン: HIGH

椅子へ座った状態で登録します。

```text
/highlow setup casino
```

中央ボタンからGUIで賭け金を選びます。同じ数字は引き分け、正解すると獲得可能額が上がり、中央ボタンで途中換金できます。

### クラッシュ

GSitの椅子と、椅子からX軸またはZ軸の一直線上に開始・換金兼用ボタンを配置します。椅子へ座って登録します。

```text
/crash setup casino
```

ボタンからGUIで賭け金を選ぶと倍率が上昇します。クラッシュ前に同じボタンを押すと換金できます。既定では1.20倍から換金可能です。

### 物理ルーレット

16個の金ブロックを停止地点として使います。帰還位置でプレイボタンを見ながら登録を始めます。

```text
/roulette setup casino next
```

地下などの観戦位置で物理台の正面を向いて、次を実行します。

```text
/roulette setview casino <台ID>
```

GUI上側の左に対応する金ブロックから時計回りに16個を右クリックします。観戦者向けの賭け情報を表示したい位置に立って、表示位置も登録します。

```text
/roulette setdisplay casino <台ID>
```

開始ボタンを押すと観戦位置へ移動し、GUIから1,000円、10,000円、50,000円のいずれかと賭け先を選びます。赤黒、奇偶、LOW/HIGHは2倍、4数字区画は4倍、数字1点は16倍です。

使用中の台へ参加すると待機列へ入り、終了後に参加順でGUIが開きます。順番が来てから既定1分間操作しない場合は受付へ戻され、次のプレイヤーへ順番が移ります。観戦用TextDisplayには現在のプレイヤー、賭け先、賭け金がリアルタイム表示されます。

### 説明看板

各ゲームの看板を見ながら`guide`コマンドを実行すると、クリックでルールを確認できる発光看板になります。

```text
/slot guide casino
/highlow guide casino
/crash guide casino
/roulette guide casino
```

解除は各コマンドの`unguide`です。

## コマンド

### 会場管理

| コマンド | 説明 |
| --- | --- |
| `/gamblevenue create <会場ID> <入場料>` | 会場を作成 |
| `/gamblevenue delete <会場ID>` | 会場を削除 |
| `/gamblevenue setfee <会場ID> <入場料>` | 入場料を変更 |
| `/gamblevenue addregion <会場ID>` | WorldEdit選択を通常リージョンへ追加 |
| `/gamblevenue clearregions <会場ID>` | 通常リージョンを全削除 |
| `/gamblevenue addvipregion <会場ID>` | WorldEdit選択をVIPリージョンへ追加 |
| `/gamblevenue clearvipregions <会場ID>` | VIPリージョンを全削除 |
| `/gamblevenue creategate <会場ID> <ゲートID>` | ゲートを作成 |
| `/gamblevenue setgateblock <会場ID> <ゲートID> <entrance\|exit>` | ゲートブロックを登録 |
| `/gamblevenue setdestination <会場ID> <ゲートID> <entrance\|exit>` | 着地点を上書き |
| `/gamblevenue setlogoutdestination <会場ID>` | ログアウト・強制退出先を登録 |
| `/gamblevenue addpassmachine <会場ID>` | 見ているボタンをパス自販機に登録 |
| `/gamblevenue addbgmbutton <会場ID>` | 見ているボタンをBGM設定に登録 |
| `/gamblevenue addfireworkpoint <会場ID>` | 現在地を花火発射地点に追加 |
| `/gamblevenue info <会場ID>` | 会場情報を表示 |
| `/gamblevenue reload` | 設定とランタイム状態を再読み込み |

各管理コマンドの完全な一覧と引数は、ゲーム内で`/<コマンド> help`を実行すると確認できます。

### ゲーム管理

| コマンド | 主な用途 |
| --- | --- |
| `/slot setup <会場ID> <賭け金>` | 着席中の椅子をスロットとして登録・更新 |
| `/slot setbet <会場ID> <台ID> <賭け金>` | スロットの賭け金変更 |
| `/highlow setup <会場ID>` | 着席中の椅子をハイ＆ローとして登録・更新 |
| `/crash setup <会場ID>` | 着席中の椅子をクラッシュとして登録・更新 |
| `/roulette setup <会場ID> <台ID\|next>` | ルーレット登録を開始 |
| `/roulette setview <会場ID> <台ID>` | 観戦位置を登録して16マス選択を開始 |
| `/roulette setdisplay <会場ID> <台ID>` | 観戦用賭け情報の表示位置を登録 |

各ゲームには`delete`、`info`、`guide`、`unguide`もあります。

### 収支統計

| コマンド | 説明 |
| --- | --- |
| `/casinoaccount total [会場ID]` | 累計の受取額・支払額・収支 |
| `/casinoaccount today [会場ID]` | 本日の受取額・支払額・収支 |
| `/casinoaccount history <会場ID> [日数]` | 会場の日別収支 |
| `/casinoaccount player <名前\|UUID> [会場ID]` | プレイヤー別の累計・本日収支 |
| `/casinoaccount playerhistory <名前\|UUID> <会場ID> [日数]` | プレイヤー別の日別収支 |
| `/casinoaccount reset <会場ID>` | 対象会場の統計を0へ戻す |

統計は支払い能力を制限する仮想口座ではありません。収支がマイナスでも会場は営業を続けます。

## 権限

| 権限 | 既定値 | 説明 |
| --- | --- | --- |
| `gamblegate.admin` | OP | 会場管理 |
| `gamblegate.passadmin` | OP | OPによるパス破棄・期限切れ |
| `gamblegate.slotadmin` | OP | スロット管理 |
| `gamblegate.highlowadmin` | OP | ハイ＆ロー管理 |
| `gamblegate.crashadmin` | OP | クラッシュ管理 |
| `gamblegate.rouletteadmin` | OP | ルーレット管理 |
| `gamblegate.accountadmin` | OP | 収支統計の閲覧・リセット |
| `gamblegate.vip` | OP | 全会場のVIPエリアへ入場 |
| `gamblegate.vip.<会場ID>` | 権限プラグインで付与 | 指定会場のVIPエリアへ入場 |
| `gamblegate.bypass` | OP | 入場・VIP制限をバイパス |

## 設定とデータ

| パス | 内容 |
| --- | --- |
| `plugins/GambleGate/config.yml` | 会場、ゲート、価格、確率、演出、効果音 |
| `plugins/GambleGate/message.yml` | GUI、チャット、タイトル、看板の文言 |
| `plugins/GambleGate/passes.yml` | パス、有効期限、お試し利用履歴 |
| `plugins/GambleGate/casino-accounts.json` | 会場・プレイヤー別の収支統計 |
| `plugins/GambleGate/casino-midi/` | 入場中に再生するMIDI |
| `plugins/GambleGate/pending-*-payouts.yml` | 経済プラグインに拒否された払戻しの再試行データ |

金額は整数として扱い、小数が入力されている場合は小数点以下を切り捨てます。`message.yml`は更新時に既存の変更を維持し、不足した新規キーだけを自動追加します。

確率、RTP、賭け金、最大払戻し、表示距離、パーティクル、効果音は`config.yml`から変更できます。変更前に必ずバックアップを作成してください。

## ソースからビルド

```bash
git clone <repository-url>
cd gamble_plugin
mvn clean package
```

必要な依存関係はMavenが取得します。生成物は次の場所です。

```text
target/gamble-gate-1.0.0.jar
```

## 更新時の注意

- `plugins/GambleGate/`をバックアップしてからjarを交換してください。
- 既存の`message.yml`は上書きされず、新しいキーだけが追加されます。
- 古い設定項目の一部は起動時に自動移行・削除されます。
- 大きなバージョン変更では、リリースノートに追加の移行手順がないか確認してください。

## 不具合報告

報告には次の情報を添えてください。

- Paperの完全なバージョン（`/version`）
- GambleGate、WorldEdit、Vault、経済プラグイン、GSitのバージョン
- 再現手順
- 関連する`config.yml`部分
- 省略していないエラーログ

プレイヤーデータ、UUID、サーバーIP、データベース認証情報などの機密情報は、公開Issueへ貼らないでください。
