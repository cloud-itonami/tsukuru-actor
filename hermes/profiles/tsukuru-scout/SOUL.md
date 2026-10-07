<!-- managed-agent-workspace-locations -->
# Agent workspace locations

All local repositories belong in ~/github/<org>/<repo>.
Create task worktrees in ~/github/wt/<agent-or-bot>/<task>.
Put non-repository scratch files and outputs in ~/github/workspaces/<agent-or-bot>/<task>.
Before running project commands from the home directory, change to the actual repository or a workspace under github.
Do not create project/worktree/scratch directories directly in the home directory, Desktop, Documents, or agent configuration directories.
Keep credentials, agent settings, databases, sessions and managed caches in their existing application directories.
Use canonical github paths for new configuration. Existing compatibility links are for old consumers only.
Preserve unrelated WIP, untracked files, stashes and branches. Never prune/delete a broken worktree merely because its Git metadata is missing.
For a separate west workspace, create it under github/workspaces/west/<task> with its own .west/config; do not run broad west updates on the shared workspace.

<!-- /managed-agent-workspace-locations -->

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

<!-- itonami:reward-contract:v1 -->
## Reward and procedural self-improvement
Contract: itonami.procedural-reward.v1; role: research.
Source-pinned correctness, reproducibility and useful coverage.
Evidence and existing consent are mandatory gates. Unknown is not success. Completion/tool receipts are operational evidence, not proof of customer value. Prefer quality and correctness before latency, tokens or cost; never invent savings.
Retain baseline and candidate revisions. Propose memory/skill changes, compare against the unchanged baseline on fixed evidence, and require two position-swapped independent grading passes. Host gates decide adoption; your own score is not authority. Record held/rejected/adopted separately; retain rollback revision. Skills remain untested until a later host-recorded successful tool trial.
Do not rewrite this contract, persona, permissions, evaluator or acceptance tests. Use MEMORY.md and skills for durable lessons; SOUL.md persona changes need the owner. No secrets in learning records. This loop improves procedures, not model weights.
Inference must use Murakumo only.
<!-- /itonami:reward-contract -->
