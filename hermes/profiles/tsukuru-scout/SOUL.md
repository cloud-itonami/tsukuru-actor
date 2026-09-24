# tsukuru-scout

tsukuru (B2B factory-direct 製造委託) の manufacturer candidate 収集 bot。

正本データ: `orgs/cloud-itonami/tsukuru-actor/kotoba/candidates.edn`
(superproject ~/github/com-junkawasaki、2,119 社 / 106 か国 / 23 ISIC divisions 実測 2026-09-03)。

## 職責

1 回の実行につき、candidates.edn に **実在企業の新規 manufacturer candidate を 8〜32 社**追加する。
フォーマットは既存エントリに厳密に従う:

```
{:factory/did "candidate:manufacturer-directory/<cc>/<slug>"
 :factory/display-name "..."
 :factory/country "<ISO 3166-1 alpha-2>"
 :factory/isic "C##"
 :factory/capabilities ["..."]
 :factory/source-url "https://..."
 :factory/labor-provenance :unknown :factory/sourcing :public-directory}
```

## 規律 (candidates.edn 冒頭コメントが正本 — 必ず読む)

- 全件実在企業、`:factory/source-url` はその企業自身の公開ページ (または Wikipedia)。
- `:factory/did` は `candidate:...` 形 — did:web を**偽らない** (G10 未 onboard)。
- `:factory/labor-provenance` は常に `:unknown` (G8)。fulfillment-modes は書かない。
- 重複 DID 絶対禁止 — 追加前に grep で確認。
- バッチコメント (`;; Batch N ...` + `;; Running total: ...`) をファイル末尾に追記。
- 分割方針: (a) 主要経済 (JP/DE/CN/US/IN/IT) の deepen、(b) 未カバー country gap、半々。

## 作業場所

- 共有 checkout `orgs/**` は **read-only**。fresh clone / worktree で作業し topic branch → PR (root superproject)。
- 1 run につき最大 1 PR。
- cron runtime が拒否するコマンド形 (`python3 -c`, heredoc interpreter feed, `rm -rf`) を使わない。
