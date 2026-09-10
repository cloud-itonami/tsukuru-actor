(ns tsukuru.kotoba.agent
  "tsukuru 作 — B2B factory-direct ordering cell (moved from py/ — legacy Python-era dir name;
  the actor is pure .cljc now, living alongside its kotoba data). Four handlers over the
  production lifecycle: handle-discover (spec → capability-match factory candidates), handle-
  compliance (G16 treaty + yabai screening + HS classification + export-control pre-screen, never
  auto-pass), handle-production (BTO/MTO/CTO order state machine, G14 member-principal, G2
  SBT-gated), handle-qc (pass/fail→quarantine/rework), + build-settlement-intent (G7 10% tithe,
  :intent unless member-signed G15). Pure compute; the Murakumo llm rerank (_llm_rank_factories)
  is the omitted leg — a no-op when llm is absent. classify-product/screen-export-control are
  curated-heuristic pre-screens (HS-CLASSIFICATION-TABLE / EXPORT-CONTROL-CATEGORIES) — same
  honest-stub idiom as screen-entity/resolve-treaty: real integrations inject a fn; the default
  never silently fabricates a pass. validate-run-package brings the CNT and EUV industrial
  verticals (00-contracts run-package/run-validation schemas + lexicons) to genuine executable
  parity: real business-rule checks over a run-package map (required-approval signatures,
  closed-loop telemetry/EHS-interlock presence, release-plan numeric targets, catalog step
  coverage for CNT; numeric technology-node/wafer-diameter/numerical-aperture + CAD/CAM
  handoff completeness for EUV), never just key presence. factory-datom->state bridges a kotoba
  :factory/* EDN entity (kotoba/seed.edn's 3-entry demo, or the broader illustrative real-company
  kotoba/manufacturer-registry-seed.edn) into the plain string-keyed shape handle-discover/
  capability-match already expect — no change to the matching logic itself."
  (:require [kotoba.lang.text :as str]
            [clojure.set :as set]))

(def TITHE-BPS 1000)
(def ^:private FULFILLMENT-MODES ["bto" "mto" "cto"])
(def ^:private ORDER-FLOW ["draft" "placed" "dispatched" "in-production" "qc" "ready" "shipped"])

(defn- tokens
  "Split on any non-alphanumeric into lowercase tokens ('cnc-5axis' → {cnc, 5axis})."
  [s]
  (set (re-seq #"[a-z0-9]+" (str/lower s))))

(defn- capability-match
  "Token-overlap capability scoring (a capability counts if any token appears in the spec)."
  [spec factory]
  (let [spec-tokens (tokens spec)]
    (count (filter (fn [cap] (some #(contains? spec-tokens %) (tokens cap))) (get factory "capabilities" [])))))

(defn- llm-rank-factories
  "Murakumo-only rerank by genuine fit. The llm host binding is the omitted leg → no-op."
  [_spec cands]
  cands)

(defn handle-discover [state]
  (let [spec (get state "spec" "")
        factories (get state "factories" [])
        scored (vec (sort-by #(capability-match spec %) > factories))
        cands (let [c (filterv #(> (capability-match spec %) 0) scored)] (if (seq c) c scored))]
    (merge state {"candidates" (llm-rank-factories spec cands)})))

(defn factory-datom->state
  "Bridges a kotoba :factory/* EDN entity (kotoba/seed.edn / kotoba/manufacturer-registry-
  seed.edn) into the plain string-keyed factory map handle-discover/capability-match already
  expect — same field shape as lex/factory.edn's lexicon record (factoryDid/displayName/
  country/isic/capabilities/fulfillmentModes/laborProvenance). Pure data reshape, no IO;
  \"capabilities\" is the only field capability-match itself reads, but the rest is carried
  through so callers (e.g. a future UI/host) can display the full registry entry."
  [factory-datom]
  {"factoryDid" (get factory-datom :factory/did)
   "displayName" (get factory-datom :factory/display-name)
   "country" (get factory-datom :factory/country)
   "isic" (get factory-datom :factory/isic)
   "capabilities" (vec (get factory-datom :factory/capabilities []))
   "fulfillmentModes" (mapv name (get factory-datom :factory/fulfillment-modes []))
   "laborProvenance" (some-> (get factory-datom :factory/labor-provenance) name)
   "sourcing" (some-> (get factory-datom :factory/sourcing) name)})

;; ── compliance (G16) ──────────────────────────────────────────────────────────
(defn screen-entity
  "yabai ScreenEntity (sanctions / denied-party). screen-fn injected for test/host."
  ([factory-did] (screen-entity factory-did nil))
  ([factory-did screen-fn]
   (if screen-fn
     (screen-fn factory-did)
     {"clear" nil "note" "yabai ScreenEntity not wired (R0); treat as pending"})))

(defn resolve-treaty
  "treaty.etzhayyim.com FTA/EPA authority-chain resolution."
  ([buyer-country factory-country] (resolve-treaty buyer-country factory-country nil))
  ([buyer-country factory-country treaty-fn]
   (cond
     treaty-fn (treaty-fn buyer-country factory-country)
     (and (seq buyer-country) (= buyer-country factory-country)) {"agreement" "domestic" "tariff_bps" 0}
     :else {"agreement" nil "note" "cross-border treaty not wired (R0)"})))

;; Curated heuristic HS (Harmonized System) chapter/heading table for classify-product.
;; Documented/reviewable source: kotoba/hs-classification-table.edn
;; (mirrored here as a plain data literal so this .cljc stays IO-free — no new deps, no
;; runtime file read, portable to cljs/WASM; keep the two in sync by hand). This is a
;; SMALL curated snapshot for tsukuru's actual verticals (CNC/metal machining,
;; aluminium/steel/plastics articles, electronics/communication equipment, semiconductor
;; devices, EUV/optical-precision instruments, CNT/carbon-fiber, textiles, vehicle parts,
;; radar/navigation) — NOT an authoritative customs ruling and NOT a full HS nomenclature.
;; A real tariff classifier belongs behind the injected classify-fn.
(def ^:private HS-CLASSIFICATION-TABLE
  [{"hsCode" "8457" "description" "Machining centres and CNC metal-working machine tools"
    "keywords" ["cnc" "machining" "milling" "lathe" "drilling"]}
   {"hsCode" "7616" "description" "Other articles of aluminium"
    "keywords" ["aluminum" "aluminium" "anodized" "anodizing" "bracket"]}
   {"hsCode" "7326" "description" "Other articles of iron or steel"
    "keywords" ["steel" "welding" "sheetmetal" "ironwork" "stamping"]}
   {"hsCode" "3926" "description" "Other articles of plastics"
    "keywords" ["plastic" "polymer" "injection" "molding" "moulded"]}
   {"hsCode" "8517" "description" "Telephone sets and apparatus for transmission/reception of voice or data"
    "keywords" ["telecom" "communication" "modem" "router" "antenna" "transceiver"]}
   {"hsCode" "8541" "description" "Diodes, transistors and similar semiconductor devices"
    "keywords" ["semiconductor" "wafer" "transistor" "diode" "asic" "circuit"]}
   {"hsCode" "8486" "description" "Machines/apparatus for manufacture of semiconductor devices or wafers (incl. EUV lithography)"
    "keywords" ["euv" "lithography" "photomask" "waferstepper" "opticalinstrument"]}
   {"hsCode" "9013" "description" "Lasers and other optical appliances/instruments"
    "keywords" ["laser" "optical" "lens" "photonics" "precisionoptics"]}
   {"hsCode" "6815" "description" "Articles of carbon fibres and other carbon/graphite articles"
    "keywords" ["carbonfiber" "nanotube" "cnt" "graphene" "fiber" "fibre"]}
   {"hsCode" "5407" "description" "Woven fabrics of synthetic filament yarn"
    "keywords" ["textile" "fabric" "yarn" "weaving"]}
   {"hsCode" "8708" "description" "Parts and accessories of motor vehicles"
    "keywords" ["automotive" "vehicle" "chassis" "drivetrain" "axle"]}
   {"hsCode" "8526" "description" "Radar apparatus and radio navigational aid apparatus"
    "keywords" ["radar" "sonar" "navigationsystem" "gpsguidance"]}])

(defn- hs-entry-score
  "Count of an HS table entry's keyword phrases that share a token with desc-tokens
  (same token-overlap idiom as capability-match)."
  [desc-tokens entry]
  (count (filter (fn [kw] (some #(contains? desc-tokens %) (tokens kw))) (get entry "keywords" []))))

(defn classify-product
  "HS chapter/heading classification of `description` by keyword match against a small
  curated table (HS-CLASSIFICATION-TABLE, above) — heuristic only, NOT an authoritative
  customs ruling. classify-fn injected for a real future classifier/host integration.
  Never silently falls back to a fixed code: an unmatched description returns an honest
  'unclassified' result requiring manual/host classification, mirroring the screen-entity
  'not wired' honesty pattern."
  ([description] (classify-product description nil))
  ([description classify-fn]
   (if classify-fn
     (classify-fn description)
     (let [desc-tokens (tokens description)
           scored (map (fn [e] [(hs-entry-score desc-tokens e) e]) HS-CLASSIFICATION-TABLE)
           [best-score best] (first (sort-by first > scored))]
       (if (and best (pos? best-score))
         {"hsCode" (get best "hsCode") "hsDescription" (get best "description")
          "matchedKeywords" (filterv (fn [kw] (some #(contains? desc-tokens %) (tokens kw))) (get best "keywords" []))
          "classificationState" "heuristic-match"}
         {"hsCode" nil "classificationState" "unclassified"
          "note" "HS classification unmatched by curated keyword table; needs manual/host classification (not wired R0)"})))))

;; Curated heuristic dual-use/export-control category snapshot for screen-export-control.
;; Documented/reviewable source: kotoba/export-control-categories.edn
;; (mirrored here as data, same rationale as HS-CLASSIFICATION-TABLE). This is a SMALL,
;; HAND-CURATED keyword flag list — NOT a live BIS Entity List / Wassenaar Arrangement /
;; EAR feed. It only flags "needs licensing review"; a real licensing/export-control
;; provider must be injected via export-control-fn before any live commercial use.
(def ^:private EXPORT-CONTROL-CATEGORIES
  [{"category" "cryptographic-hardware"
    "reason" "cryptographic/encryption hardware and components are commonly subject to dual-use export licensing"
    "keywords" ["encryption" "cryptographic" "cryptography" "cipher" "hsm"]}
   {"category" "night-vision-thermal-imaging"
    "reason" "night-vision / thermal-imaging optics are commonly controlled dual-use goods"
    "keywords" ["nightvision" "thermalimaging" "infrared" "imageintensifier"]}
   {"category" "radar-navigation-systems"
    "reason" "radar / navigation-guidance apparatus commonly falls under dual-use export-control regimes"
    "keywords" ["radar" "sonar" "navigationsystem" "gpsguidance"]}
   {"category" "euv-lithography-equipment"
    "reason" "EUV lithography and advanced semiconductor manufacturing equipment is a well-known focal point of export-control regimes"
    "keywords" ["euv" "lithography" "waferstepper" "photomask"]}])

(defn screen-export-control
  "Heuristic dual-use/export-control PRE-SCREEN of `description` against a small curated
  category table (EXPORT-CONTROL-CATEGORIES, above) — flags 'needs licensing review'.
  THIS IS NOT a live BIS Entity List / Wassenaar Arrangement / EAR check; it is a coarse
  keyword snapshot, distinct from screen-entity's denied-party/sanctions check.
  export-control-fn injected for a real licensing/export-control integration."
  ([description] (screen-export-control description nil))
  ([description export-control-fn]
   (if export-control-fn
     (export-control-fn description)
     (let [desc-tokens (tokens description)
           hit (first (filter (fn [e] (some (fn [kw] (some #(contains? desc-tokens %) (tokens kw))) (get e "keywords" [])))
                               EXPORT-CONTROL-CATEGORIES))]
       (if hit
         {"flagged" true "category" (get hit "category") "reason" (get hit "reason")
          "note" "heuristic dual-use keyword match; needs licensing/export-control review (not a live BIS/Wassenaar check, R0)"}
         {"flagged" false
          "note" "no dual-use keyword match in curated table (heuristic only; not a live BIS/Wassenaar check, R0)"})))))

(defn handle-compliance
  "Compose screen-entity + resolve-treaty + classify-product + screen-export-control into
  the G16 gate. Never auto-passes: unresolved screening, an unclassified product, or a
  flagged dual-use category all land in 'pending' (no silent pass); only an explicit
  denied-party hit hard-rejects. classify-fn/export-control-fn injected like screen-fn/
  treaty-fn (nil by default)."
  ([state] (handle-compliance state nil nil nil nil))
  ([state screen-fn treaty-fn] (handle-compliance state screen-fn treaty-fn nil nil))
  ([state screen-fn treaty-fn classify-fn export-control-fn]
   (let [screen (screen-entity (get state "factoryDid" "") screen-fn)
         treaty (resolve-treaty (get state "buyerCountry" "") (get state "factoryCountry" "") treaty-fn)
         spec (get state "spec" "")
         classification (classify-product spec classify-fn)
         export-flag (screen-export-control spec export-control-fn)]
     (cond
       (= (get screen "clear") false)
       (merge state {"complianceState" "rejected" "complianceNote" (str "yabai denied-party hit: " (get screen "note" ""))})

       (true? (get export-flag "flagged"))
       (merge state {"complianceState" "pending"
                     "complianceNote" (str "export-control category flagged: " (get export-flag "category")
                                            " — " (get export-flag "reason") " (G16, needs licensing review, no auto-pass)")})

       (nil? (get classification "hsCode"))
       (merge state {"complianceState" "pending"
                     "complianceNote" (str "HS classification unresolved: " (get classification "note" "") " (G16, no auto-pass)")})

       (= (get screen "clear") true)
       (merge state {"complianceState" "passed"
                     "complianceNote" (str "yabai clear; treaty=" (get treaty "agreement")
                                            "; hsCode=" (get classification "hsCode") "; exportControl=clear")})

       :else
       (merge state {"complianceState" "pending" "complianceNote" (str "screening unresolved: " (get screen "note" "") " (G16, no auto-pass)")})))))

;; ── production (order state machine) ──────────────────────────────────────────
(defn check-sbt-eligibility
  "Member buyer must hold an active Adherent SBT (§3 / G2)."
  [buyer-did sbt-registry]
  (let [active (boolean (get-in sbt-registry [buyer-did "active"]))]
    {"eligible" active "reason" (if active "" "buyer lacks active Adherent SBT (§3/G2)")}))

(defn create-production-order
  "okaimono create-production-order entrypoint. Member = purchasing principal (G14)."
  ([buyer-did factory-did mode spec] (create-production-order buyer-did factory-did mode spec nil))
  ([buyer-did factory-did mode spec sbt-registry]
   (if-not (contains? (set FULFILLMENT-MODES) mode)
     {"state" "refused" "reason" (str "unknown fulfillment mode '" mode "'")}
     (let [elig (check-sbt-eligibility buyer-did (or sbt-registry {}))]
       (if-not (get elig "eligible")
         {"state" "refused" "reason" (get elig "reason")}
         {"buyerDid" buyer-did "factoryDid" factory-did "mode" mode "spec" spec
          "state" "placed" "complianceState" "pending"})))))

(defn advance-order
  "Advance one stage along ORDER-FLOW. Compliance must pass before dispatch (G16)."
  [order]
  (let [st (get order "state" "draft")]
    (cond
      (contains? #{"refused" "quarantine"} st) order
      (and (= st "placed") (not= (get order "complianceState") "passed"))
      (merge order {"blocked" "compliance not passed (G16)"})
      :else (let [idx (.indexOf ORDER-FLOW st)]
              (if (or (neg? idx) (>= (inc idx) (count ORDER-FLOW)))
                order
                (merge order {"state" (nth ORDER-FLOW (inc idx)) "blocked" nil}))))))

(defn- stage-pct [stage]
  (get {"draft" 0 "placed" 5 "dispatched" 15 "in-production" 60 "qc" 90 "ready" 98 "shipped" 100 "quarantine" 90} stage 0))

(defn handle-production [state]
  (let [order (or (get state "order")
                  (create-production-order (get state "buyerDid" "") (get state "factoryDid" "")
                                           (get state "mode" "bto") (get state "spec" "") (get state "sbtRegistry" {})))
        advanced (advance-order order)
        progress {"order" (get advanced "buyerDid" "") "stage" (get advanced "state")
                  "pct" (stage-pct (get advanced "state" "draft")) "timestamp" (get state "now" "")}]
    (merge state {"order" advanced "progress" progress})))

;; ── qc ──────────────────────────────────────────────────────────────────────
(defn handle-qc
  ([state] (handle-qc state nil))
  ([state inspect-fn]
   (let [order (get state "order" {})
         defects (or (if inspect-fn (inspect-fn order) (get state "defects")) [])
         [result order*] (if (seq defects)
                           (let [res (if (get state "reworkable" true) "rework" "fail")
                                 ns (if (= res "rework") "in-production" "quarantine")]
                             [res (merge order {"state" ns})])
                           ["pass" (merge order {"state" "ready"})])
         quality {"order" (get order* "buyerDid" "") "result" result "defects" defects
                  "inspectorDid" (get state "inspectorDid" "") "timestamp" (get state "now" "")}]
     (merge state {"order" order* "quality" quality}))))

(defn build-settlement-intent
  "USDC settlement split. 10% tithe → Public Fund; factory gets the net. Stops at :intent —
  broadcast needs a member signature (G15) + operator gate (G11)."
  ([gross-minor] (build-settlement-intent gross-minor nil))
  ([gross-minor buyer-sig-ref]
   (let [tithe (quot (* gross-minor TITHE-BPS) 10000)]
     {"rail" "usdc-base-l2" "grossMinor" gross-minor "titheMinor" tithe
      "factoryPayoutMinor" (- gross-minor tithe) "titheRouter" "50-infra/etzhayyim-tithe-router"
      "state" (if buyer-sig-ref "executed" "intent") "buyerSigRef" (or buyer-sig-ref "")})))

;; ── process-run validation (CNT / EUV run-package → run-validation) ──────────────────────
;; Both CNT (wire/schemas/tsukuru-cnt-run-{package,validation}.schema.json +
;; 00-contracts/catalogs/.../cnt/process-catalog.v1.json) and EUV (00-contracts/lexicons/
;; .../euv/*.json — no run-package/run-validation schema exists yet) industrial verticals are
;; documented at the AT-proto contract layer but, until now, had NO executable business-rule
;; validation in this living actor — 00-contracts only carries static JSON Schema (structural
;; validation: "does the shape match", not "is the recipe actually releasable"). The two
;; catalog step-id sets below mirror kotoba/cnt-process-catalog.edn / kotoba/
;; euv-process-catalog.edn (same "embed as a Clojure literal, keep the EDN in sync by hand"
;; rationale as HS-CLASSIFICATION-TABLE/EXPORT-CONTROL-CATEGORIES above — IO-free, portable).

(def ^:private CNT-CATALOG-STEP-IDS
  "The 6 tsukuru_cnt_fiber_manufacturing_flow catalog step ids, ported from
  wire/catalogs/cnt/process-catalog.v1.json / kotoba/cnt-process-catalog.edn."
  #{"step-010-feedstock-catalyst-intake" "step-020-floating-cvd-growth" "step-030-aerogel-collection"
    "step-040-draw-spin-densify" "step-050-anneal-dope-size" "step-060-release-metrology"})

(def ^:private EUV-CATALOG-STEP-IDS
  "The 5 tsukuru_euv_lithography_manufacturing_flow catalog step ids modeled in
  kotoba/euv-process-catalog.edn (new — EUV has no 00-contracts process-catalog JSON yet)."
  #{"step-010-wafer-prep" "step-020-euv-exposure" "step-030-develop" "step-040-etch" "step-050-metrology-inspection"})

(defn- recipe-steps [run-package] (get run-package "recipe" []))
(defn- recipe-step-ids [run-package] (set (map #(get % "stepId") (recipe-steps run-package))))
(defn- steps-with-mode [run-package mode] (filterv #(= mode (get % "automationMode")) (recipe-steps run-package)))

(defn- unsigned-required-approvals
  "Required approvals (\"required\" true) without a non-blank \"did\" signature."
  [run-package]
  (filterv (fn [a] (and (true? (get a "required")) (str/blank? (get a "did" ""))))
           (get run-package "approvals" [])))

(defn- required-approval-count [run-package]
  (count (filter #(true? (get % "required")) (get run-package "approvals" []))))

(defn- coverage-check
  "recipe-covers-catalog-steps: the run-package recipe must include every catalog step id —
  a genuine coverage check against kotoba/{cnt,euv}-process-catalog.edn, not just non-empty."
  [catalog-step-ids run-package]
  (let [missing (set/difference catalog-step-ids (recipe-step-ids run-package))]
    {"id" "recipe-covers-catalog-steps" "passed" (empty? missing)
     "detail" (if (empty? missing)
                "recipe covers all catalog process steps"
                (str "recipe missing catalog step(s): " (str/join ", " (sort missing))))}))

(defn- approvals-check
  "required-approvals-signed: every required approval role needs a DID signature before ready
  (mirrors 00-contracts run-validation.example.v1.json's own blocked-by-approvals example)."
  [run-package]
  (let [unsigned (unsigned-required-approvals run-package)]
    {"id" "required-approvals-signed" "passed" (empty? unsigned)
     "detail" (if (empty? unsigned)
                "all required approval roles have a DID signature"
                (str "unsigned required approval role(s): " (str/join ", " (map #(get % "role") unsigned))))}))

(defn- dry-run-metrics [run-package]
  {"estimatedDurationMin" 0 ;; not modeled at R0 — honest zero, never a fabricated estimate
   "closedLoopStepCount" (count (steps-with-mode run-package "closed-loop"))
   "humanGatedStepCount" (count (steps-with-mode run-package "human-gated"))
   "telemetryTagCount" (count (get run-package "telemetryBinding" []))
   "requiredApprovalCount" (required-approval-count run-package)})

(defn- run-validation-result
  "Assemble the run-validation-shaped result common to CNT/EUV from a vector of checks."
  [vertical run-package checks]
  (let [blockers (mapv #(get % "id") (filter #(false? (get % "passed")) checks))]
    {"schema" (str "tsukuru." vertical "RunValidation.v1")
     "runId" (get run-package "runId" "")
     "status" (if (empty? blockers) "ready" "blocked")
     "blockers" blockers
     "warnings" (cond-> [] (seq (steps-with-mode run-package "human-gated")) (conj "human-gated-step-present"))
     "checks" checks
     "dryRun" (dry-run-metrics run-package)}))

(defn- validate-run-package-cnt
  "CNT business rules: catalog step coverage; required approvals signed; closed-loop steps
  need at least one telemetryBinding tag with a positive sampleRateHz AND a non-empty
  ehsInterlocks list; releasePlan must carry all 4 numeric release targets."
  [run-package]
  (let [closed-loop (steps-with-mode run-package "closed-loop")
        telemetry (get run-package "telemetryBinding" [])
        release-plan (get run-package "releasePlan" {})
        release-targets ["targetStrengthGPa" "targetConductivitySM" "targetThermalConductivityWMK" "targetSpoolLengthM"]
        missing-targets (filterv #(not (number? (get release-plan %))) release-targets)
        checks
        [(coverage-check CNT-CATALOG-STEP-IDS run-package)
         (approvals-check run-package)
         {"id" "closed-loop-telemetry-bound"
          "passed" (or (empty? closed-loop)
                       (and (seq telemetry)
                            (every? #(and (number? (get % "sampleRateHz")) (pos? (get % "sampleRateHz"))) telemetry)))
          "detail" "closed-loop automation steps require at least one telemetryBinding tag with a positive sampleRateHz"}
         {"id" "ehs-interlocks-present"
          "passed" (or (empty? closed-loop) (boolean (seq (get run-package "ehsInterlocks" []))))
          "detail" "closed-loop automation steps require at least one EHS interlock tag"}
         {"id" "release-plan-targets-set"
          "passed" (empty? missing-targets)
          "detail" (if (seq missing-targets)
                     (str "releasePlan missing numeric target(s): " (str/join ", " missing-targets))
                     "releasePlan carries all numeric release targets")}]]
    (run-validation-result "cnt" run-package checks)))

(defn- supplier-exchange-referenced?
  "True when the run-package references an outbound supplier exchange (supplierDid and/or a
  cadCamHandoff.supplierExchangeFormat) — the trigger for requiring a CAD/CAM handoff format."
  [run-package]
  (let [handoff (get run-package "cadCamHandoff" {})]
    (or (not (str/blank? (get run-package "supplierDid" "")))
        (not (str/blank? (get handoff "supplierExchangeFormat" ""))))))

(defn- validate-run-package-euv
  "EUV business rules: catalog step coverage; required approvals signed; technologyNodeNm/
  waferDiameterMm/numericalAperture present and numeric; cadCamHandoff.format present once a
  supplier exchange is referenced."
  [run-package]
  (let [handoff (get run-package "cadCamHandoff" {})
        checks
        [(coverage-check EUV-CATALOG-STEP-IDS run-package)
         (approvals-check run-package)
         {"id" "technology-node-numeric" "passed" (number? (get run-package "technologyNodeNm"))
          "detail" "technologyNodeNm must be present and numeric"}
         {"id" "wafer-diameter-numeric" "passed" (number? (get run-package "waferDiameterMm"))
          "detail" "waferDiameterMm must be present and numeric"}
         {"id" "numerical-aperture-numeric" "passed" (number? (get run-package "numericalAperture"))
          "detail" "numericalAperture must be present and numeric"}
         {"id" "cad-cam-handoff-format-present"
          "passed" (or (not (supplier-exchange-referenced? run-package)) (not (str/blank? (get handoff "format" ""))))
          "detail" "cadCamHandoff.format required once a supplier exchange (supplierDid / supplierExchangeFormat) is referenced"}]]
    (run-validation-result "euv" run-package checks)))

(defn validate-run-package
  "Business-rule validation of a CNT or EUV run-package map (`vertical` = \"cnt\" | \"euv\"),
  producing a run-validation-shaped result: {\"schema\" .. \"runId\" .. \"status\"
  \"ready\"|\"blocked\" \"blockers\" [..] \"warnings\" [..] \"checks\" [..] \"dryRun\" {..}} — the
  same shape as com.etzhayyim.apps.tsukuru.cnt.validateRunPackage's output and
  00-contracts/examples/.../cnt/run-validation.example.v1.json (EUV has no schema counterpart
  yet; this is that vertical's first concrete run-validation shape). Checks REAL business
  rules (see validate-run-package-cnt/-euv docstrings) — never just key presence — and never
  silently passes: any failed check surfaces in \"blockers\" and forces \"blocked\".
  estimatedDurationMin is an honest 0 (not modeled at R0), not a fabricated estimate. An
  unrecognized vertical returns an honest \"blocked\" with an \"unsupported-vertical\" check."
  [vertical run-package]
  (case vertical
    "cnt" (validate-run-package-cnt run-package)
    "euv" (validate-run-package-euv run-package)
    (run-validation-result vertical run-package
                           [{"id" "unsupported-vertical" "passed" false
                             "detail" (str "unknown vertical '" vertical "'; expected \"cnt\" or \"euv\"")}])))
