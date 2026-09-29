# etzhayyim-project-tsukuru

B2B factory-direct ordering platform for `tsukuru.etzhayyim.com`.

## Canonical IDs and Naming Rules

- Canonical nanoid is `tsukr8u0`.
- `0ljdfw8u` is deprecated (alpha-start violation) and must not be used in new paths, hosts, component names, or deploy commands.
- Canonical app/component name is `tsukuru` (not `tsukuru-api`).

## Runtime and Endpoint Rules

- API base URL: `https://tsukr8u0.etzhayyim.com/xrpc`.
- DID root: `did:web:tsukuru.etzhayyim.com`.
- Manufacturer DID model remains path-based dynamic multi-DID (`performerType=service`).

## Manufacturer Registry Rules

- Active collection name: `com.etzhayyim.apps.tsukuru.manufacturer`.
- Historical collection for migration/read-compat: `com.etzhayyim.apps.tsukuru-api.manufacturer`.
- Registry scale assumption: 460+ manufacturer DIDs across 30+ countries.

## Write Buffer Rule

- Use unified batch-flush write-buffer path for graph writes.
- Optimization target and current reference: Shannon efficiency `η = 99.8%`.
- Avoid ad-hoc per-record flush patterns unless explicitly required for correctness.

## Required WIT Packages

- `etzhayyim:tsukuru@0.1.0`
- `etzhayyim:tsukuru-process-registry@1.0.0`
- `etzhayyim:tsukuru-manufacturer-registry@1.0.0`
- `etzhayyim:tsukuru-trade-compliance@1.0.0`
- `etzhayyim:tsukuru-production-order@1.0.0`

## Production Order (BTO/OEM)

**WIT**: `etzhayyim:tsukuru-production-order@1.0.0` — production-order, production-progress, quality-inspection

**Record kinds** (`com.etzhayyim.apps.tsukuru.*`): `production_order`, `production_progress`, `quality_inspection`

**Flow**: okaimono order (paid) → `create-production-order` → factory DID Invoke → progress updates → QC → ship

**Fulfillment modes**: `bto` (Build-to-Order), `mto` (Made-to-Order), `cto` (Configure-to-Order)

設計: `90-docs/260326-okaimono-bto-oem-manufacturing-design.md`

**Convo Integration**: `yoro.etzhayyim.com/profile/did:web:tsukr8u0.etzhayyim.com` → メッセージ → Murakumo LLM + MCP tool calling で製造プロジェクト実行。`convoSystemPrompt` (kotodama.jsonld) でガイダンス。

## CNT / CNT Fiber Process Automation

**WIT surface**: `etzhayyim:tsukuru-process-registry@1.0.0` + `etzhayyim:tsukuru-production-order@1.0.0`

**XRPC**:
- `com.etzhayyim.apps.tsukuru.cnt.designManufacturingFlow`
- `com.etzhayyim.apps.tsukuru.cnt.planAutomation`
- `com.etzhayyim.apps.tsukuru.cnt.prepareOrderPackage`
- `com.etzhayyim.apps.tsukuru.cnt.getAutomationCoverage`
- `com.etzhayyim.apps.tsukuru.cnt.getProcessCatalog`
- `com.etzhayyim.apps.tsukuru.cnt.prepareRunPackage`
- `com.etzhayyim.apps.tsukuru.cnt.validateRunPackage`

**BPMN**:
- `tsukuru_cnt_fiber_manufacturing_flow`
- `tsukuru_cnt_automation_plan`
- `tsukuru_prepare_cnt_order_package`
- `tsukuru_get_cnt_automation_coverage`
- `tsukuru_prepare_cnt_run_package`
- `tsukuru_validate_cnt_run_package`

**Open signal actors**: `open-chemicals-management`, `open-critical-minerals`,
`open-ai-supply-chain`, `open-hs`, `open-commodity-trade`, `open-cbam-embedded`,
and `open-carbon-tax` are bound as external observation inputs. They do not
replace the Tsukuru manufacturing owner; they feed compliance, supply, customs,
and embedded-carbon context into the CNT flow.

**Catalog/schema data**:
- code: `60-apps/etzhayyim-project-tsukuru/appview/tsukuru-tsukr8u0/src/cnt-process-catalog.ts`
- schema: `wire/schemas/tsukuru-cnt-process-catalog.schema.json`
- data: `wire/catalogs/cnt/process-catalog.v1.json`
- run package schema: `wire/schemas/tsukuru-cnt-run-package.schema.json`
- run package example: `wire/examples/cnt/run-package.example.v1.json`
- run validation schema: `wire/schemas/tsukuru-cnt-run-validation.schema.json`
- run validation example: `wire/examples/cnt/run-validation.example.v1.json`

## CNT / EUV run-package validation — executable parity in the living actor (`kotoba/agent.cljc`)

`00-contracts` only ever carried **static JSON Schema** for CNT (structural validation — "does
the shape match") and bare lexicons for EUV (no run-package/run-validation schema at all).
Neither vertical had any **executable business-rule** validation until `validate-run-package`
(`[vertical run-package]`, `vertical` = `"cnt"` | `"euv"`) was added to
`src/tsukuru/kotoba/agent.kotoba`, following the same honest-stub / curated-table idiom as
`classify-product`/`screen-export-control`. It returns a run-validation-shaped result
(`"schema"`/`"runId"`/`"status"`/`"blockers"`/`"warnings"`/`"checks"`/`"dryRun"`, matching
`com.etzhayyim.apps.tsukuru.cnt.validateRunPackage`'s output shape) and checks real rules —
catalog step coverage, required-approval DID signatures, closed-loop telemetry/EHS-interlock
binding, releasePlan numeric targets (CNT); numeric technology-node/wafer-diameter/
numerical-aperture + CAD/CAM handoff completeness (EUV) — never just key presence, and never
a silent pass (`estimatedDurationMin` is an honest `0`, not modeled at R0).

- kotoba-native backing data: `kotoba/cnt-process-catalog.edn` (ported from the JSON catalog
  above), `kotoba/euv-process-catalog.edn` (new — modeled from the EUV lexicons' technology-
  node/NA/source-power/wafer-diameter parameters; EUV has no process-catalog JSON yet),
  `kotoba/cnt-run-package-seed.edn` / `kotoba/euv-run-package-seed.edn` (worked "ready"
  examples, R0, not live commercial offers).
- EDN lexicon mirrors: `lex/cnt.edn` (7 query lexicons), `lex/euv.edn` (3 query lexicons).
- kotoba schema: `kotoba/schema.edn`'s `:process-run/*` namespace (`:process-run/vertical`
  discriminates `:cnt` | `:euv`).
- tests: `kotoba/test_agent.cljc` — green + blocked coverage for both verticals.

## Cross-Project Dependencies

| Project | Integration | Purpose |
|---|---|---|
| `etzhayyim-project-cpc` | WIT bidirectional dependency | CPC process resolution and performer linking |
| `etzhayyim-project-resources` | XRPC `CreateResource` | Supplier/resource synchronization |
| `etzhayyim-project-legal-entity` | `Invoke` LEI lookup | Legal entity verification |
| `etzhayyim-project-yabai` | `Invoke` `ScreenEntity` | Sanctions and denied-party screening |
| `etzhayyim-project-trust` | `Invoke` `GetTrustScore` | DID trust scoring |
| `etzhayyim-project-completer` | `Invoke` `EvaluateCompliance` | Trade/regulatory compliance evaluation |
| `etzhayyim-project-treaty` | Authority chain | FTA/EPA trade agreement resolution |
| `etzhayyim-project-industry-standard` | Authority chain follow | ISO and industry standard tracking |
| `etzhayyim-project-maps` | Graph `:LOCATED_IN` relation | Factory geolocation linkage |
| `etzhayyim-project-supply-chain` | Graph `:SUPPLIES` relation | Upstream/downstream risk and supplier graph |
| `etzhayyim-project-okaimono` | Catalog integration | Factory-direct catalog federation |

## Build and Deploy

```bash
cd 60-apps/etzhayyim-project-tsukuru/wasm/tsukuru-tsukr8u0
etzhayyim build
etzhayyim deploy --smoke-url https://tsukr8u0.etzhayyim.com/health
```

## Storage and Access Rules

- Graph access must go through `G()` builder only.
- Keep fallback behavior explicit and minimal when graph is unavailable.
