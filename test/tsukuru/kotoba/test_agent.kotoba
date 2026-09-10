(ns tsukuru.kotoba.test-agent
  "tsukuru 作 — agent cell tests (moved from py/ — legacy Python-era dir name; pure .cljc now).
  Offline: discover capability ranking, SBT gate (§3/G2), unknown-mode refusal, compliance no-auto-
  pass + denied-party reject (G16), HS classification (classify-product) + export-control
  pre-screen (screen-export-control) honest-stub coverage, order advance gating, QC
  fail→quarantine / pass→ready, USDC + tithe settlement (G7/G11/G15), CNT/EUV run-package
  business-rule validation (validate-run-package) green + blocked coverage, and real-
  manufacturer registry coverage (factory-datom->state + handle-discover over a
  kotoba/manufacturer-registry-seed.edn slice)."
  (:require [clojure.test :refer [deftest is]]
            [tsukuru.kotoba.agent :as agent]))

(deftest test-discover-ranks-by-capability
  (let [out (agent/handle-discover {"spec" "5-axis CNC aluminum bracket, anodized black"
                                    "factories" [{"factoryDid" "inj" "capabilities" ["injection-molding"]}
                                                 {"factoryDid" "cnc" "capabilities" ["cnc-5axis" "anodizing"]}]})]
    (is (= "cnc" (get (first (get out "candidates")) "factoryDid")))))

;; ── real-manufacturer registry coverage (kotoba/manufacturer-registry-seed.edn) ──────────────
;; A verbatim slice (same :factory/* values) of kotoba/manufacturer-registry-seed.edn's
;; illustrative real-company entries — kept as inline data here (not read from disk) so this
;; .cljc stays IO-free/portable, same rationale as HS-CLASSIFICATION-TABLE/EXPORT-CONTROL-
;; CATEGORIES in agent.cljc. Proves the new registry's :factory/* shape flows unmodified through
;; factory-datom->state → handle-discover/capability-match. Per that file's header: illustrative
;; registry seed drawn from public knowledge, NOT a verified/onboarded factory relationship, NOT
;; a live commercial offer; :factory/did values are placeholder "factory/registry-seed/<slug>"
;; ids, distinct from the live did:web:tsukuru.etzhayyim.com/... scheme.
(def ^:private real-manufacturer-registry-slice
  [{:factory/did "factory/registry-seed/asml" :factory/display-name "ASML Holding N.V."
    :factory/country "NL" :factory/isic "C28"
    :factory/capabilities ["euv-lithography" "wafer-stepper" "lithography-equipment"]
    :factory/labor-provenance :unknown :factory/sourcing :representative}
   {:factory/did "factory/registry-seed/tokyo-electron" :factory/display-name "Tokyo Electron Limited"
    :factory/country "JP" :factory/isic "C28"
    :factory/capabilities ["semiconductor-equipment" "euv-lithography" "etch" "deposition"]
    :factory/labor-provenance :unknown :factory/sourcing :representative}
   {:factory/did "factory/registry-seed/tsmc" :factory/display-name "Taiwan Semiconductor Manufacturing Company Limited (TSMC)"
    :factory/country "TW" :factory/isic "C26"
    :factory/capabilities ["wafer-fab" "semiconductor-foundry" "packaging" "test"]
    :factory/labor-provenance :unknown :factory/sourcing :representative}
   {:factory/did "factory/registry-seed/bosch" :factory/display-name "Robert Bosch GmbH"
    :factory/country "DE" :factory/isic "C29"
    :factory/capabilities ["automotive-parts" "sensors" "electronics"]
    :factory/labor-provenance :unknown :factory/sourcing :representative}
   {:factory/did "factory/registry-seed/foxconn" :factory/display-name "Foxconn (Hon Hai Precision Industry Co., Ltd.)"
    :factory/country "TW" :factory/isic "C26"
    :factory/capabilities ["ems" "contract-manufacturing" "smt-assembly" "final-assembly"]
    :factory/labor-provenance :unknown :factory/sourcing :representative}])

(deftest test-factory-datom->state-reshapes-kotoba-entity
  (let [out (agent/factory-datom->state (first real-manufacturer-registry-slice))]
    (is (= "factory/registry-seed/asml" (get out "factoryDid")))
    (is (= ["euv-lithography" "wafer-stepper" "lithography-equipment"] (get out "capabilities")))
    (is (= "unknown" (get out "laborProvenance")))
    (is (= "representative" (get out "sourcing")))))

(deftest test-discover-ranks-real-manufacturer-registry-slice
  (let [out (agent/handle-discover
              {"spec" "EUV lithography wafer stepper system for advanced logic node"
               "factories" (mapv agent/factory-datom->state real-manufacturer-registry-slice)})
        cands (get out "candidates")]
    (is (= "ASML Holding N.V." (get (first cands) "displayName")))
    (is (some #{"Tokyo Electron Limited"} (map #(get % "displayName") cands)))
    (is (some #{"Taiwan Semiconductor Manufacturing Company Limited (TSMC)"} (map #(get % "displayName") cands)))
    (is (not (some #{"Robert Bosch GmbH"} (map #(get % "displayName") cands))))
    (is (not (some #{"Foxconn (Hon Hai Precision Industry Co., Ltd.)"} (map #(get % "displayName") cands))))))

(deftest test-sbt-gate-refuses-non-member
  (is (= "refused" (get (agent/create-production-order "did:web:stranger" "f1" "bto" "x" {}) "state"))))

(deftest test-unknown-mode-refused
  (let [reg {"did:web:m" {"active" true}}]
    (is (= "refused" (get (agent/create-production-order "did:web:m" "f1" "zzz" "x" reg) "state")))))

(deftest test-compliance-no-auto-pass-when-unresolved
  (let [out (agent/handle-compliance {"factoryDid" "f1" "buyerCountry" "JP" "factoryCountry" "TW"})]
    (is (= "pending" (get out "complianceState")))))

(deftest test-compliance-denied-party-rejects
  (let [out (agent/handle-compliance {"factoryDid" "bad"} (fn [_did] {"clear" false "note" "OFAC SDN"}) nil)]
    (is (= "rejected" (get out "complianceState")))))

;; ── HS classification (classify-product) ─────────────────────────────────────
(deftest test-classify-product-matches-keyword
  (let [out (agent/classify-product "Carbon nanotube (CNT) fiber spool for automated fiber placement")]
    (is (= "6815" (get out "hsCode")))
    (is (= "heuristic-match" (get out "classificationState")))))

(deftest test-classify-product-unclassified-stays-honest
  (let [out (agent/classify-product "artisanal ceramic decorative vase")]
    (is (nil? (get out "hsCode")))
    (is (= "unclassified" (get out "classificationState")))))

;; ── export-control pre-screen (screen-export-control) ────────────────────────
(deftest test-screen-export-control-flags-controlled-category
  (let [out (agent/screen-export-control "EUV lithography wafer stepper photomask system")]
    (is (true? (get out "flagged")))
    (is (= "euv-lithography-equipment" (get out "category")))))

(deftest test-screen-export-control-clear-for-ordinary-product
  (let [out (agent/screen-export-control "CNC 5-axis aluminum bracket, anodized black")]
    (is (false? (get out "flagged")))))

;; ── compliance (G16) — export-control + HS classification wired in ───────────
(deftest test-compliance-export-control-flag-blocks-pending
  (let [out (agent/handle-compliance
              {"factoryDid" "f1" "spec" "EUV lithography wafer stepper photomask system"}
              (fn [_did] {"clear" true})
              nil)]
    (is (= "pending" (get out "complianceState")))))

(deftest test-compliance-clean-product-passes
  (let [out (agent/handle-compliance
              {"factoryDid" "f1" "spec" "CNC 5-axis aluminum bracket, anodized black"}
              (fn [_did] {"clear" true})
              nil)]
    (is (= "passed" (get out "complianceState")))))

(deftest test-advance-blocked-until-compliance-passed
  (let [out (agent/advance-order {"state" "placed" "complianceState" "pending"})]
    (is (some? (get out "blocked")))
    (is (= "placed" (get out "state")))))

(deftest test-advance-proceeds-after-compliance
  (let [out (agent/advance-order {"state" "placed" "complianceState" "passed"})]
    (is (= "dispatched" (get out "state")))))

(deftest test-qc-fail-diverts-to-quarantine
  (let [out (agent/handle-qc {"order" {"state" "qc"} "defects" ["crack"] "reworkable" false})]
    (is (= "quarantine" (get-in out ["order" "state"])))))

(deftest test-qc-pass-marks-ready
  (let [out (agent/handle-qc {"order" {"state" "qc"} "defects" []})]
    (is (= "ready" (get-in out ["order" "state"])))
    (is (= "pass" (get-in out ["quality" "result"])))))

(deftest test-settlement-tithe-split
  (let [s (agent/build-settlement-intent 500000000)]
    (is (= 50000000 (get s "titheMinor")))
    (is (= 450000000 (get s "factoryPayoutMinor")))
    (is (= "intent" (get s "state")))
    (is (= "usdc-base-l2" (get s "rail")))))

(deftest test-settlement-executed-only-with-member-sig
  (is (= "executed" (get (agent/build-settlement-intent 1000000 "0xmembersig") "state"))))

;; ── CNT/EUV run-package validation (validate-run-package) ────────────────────
(def ^:private cnt-run-package-green
  {"runId" "cnt-run-test-green" "productionOrderId" "po-cnt-test-001"
   "processId" "tsukuru_cnt_fiber_manufacturing_flow" "recipeRevision" "cnt-fiber-recipe-r1"
   "productFamily" "cnt-fiber"
   "recipe" [{"stepId" "step-010-feedstock-catalyst-intake" "sequence" 10 "automationMode" "human-gated"}
             {"stepId" "step-020-floating-cvd-growth" "sequence" 20 "automationMode" "closed-loop"}
             {"stepId" "step-030-aerogel-collection" "sequence" 30 "automationMode" "closed-loop"}
             {"stepId" "step-040-draw-spin-densify" "sequence" 40 "automationMode" "closed-loop"}
             {"stepId" "step-050-anneal-dope-size" "sequence" 50 "automationMode" "human-gated"}
             {"stepId" "step-060-release-metrology" "sequence" 60 "automationMode" "human-gated"}]
   "telemetryBinding" [{"tag" "CNT.REACTOR.ZONE_TEMP_C" "source" "plc" "sampleRateHz" 1 "retention" "7y"}]
   "releasePlan" {"targetStrengthGPa" 2 "targetConductivitySM" 100000
                  "targetThermalConductivityWMK" 100 "targetSpoolLengthM" 1000
                  "disposition" "hold-for-release"}
   "ehsInterlocks" ["CNT.SAFETY.LEL_PERCENT" "CNT.REACTOR.DOOR_INTERLOCK"]
   "approvals" [{"role" "manufacturing-owner" "required" true "did" "did:web:tsukuru.etzhayyim.com:operator-example"}
                {"role" "ehs-owner" "required" true "did" "did:web:tsukuru.etzhayyim.com:ehs-example"}
                {"role" "quality-owner" "required" true "did" "did:web:tsukuru.etzhayyim.com:quality-example"}]})

(deftest test-validate-run-package-cnt-green-is-ready
  (let [out (agent/validate-run-package "cnt" cnt-run-package-green)]
    (is (= "ready" (get out "status")))
    (is (empty? (get out "blockers")))))

(deftest test-validate-run-package-cnt-missing-approval-is-blocked
  (let [blocked (update cnt-run-package-green "approvals"
                        (fn [approvals] (mapv #(if (= "ehs-owner" (get % "role")) (assoc % "did" "") %) approvals)))
        out (agent/validate-run-package "cnt" blocked)]
    (is (= "blocked" (get out "status")))
    (is (some #{"required-approvals-signed"} (get out "blockers")))))

(def ^:private euv-run-package-green
  {"runId" "euv-run-test-green" "productionOrderId" "po-euv-test-001"
   "processId" "tsukuru_euv_lithography_manufacturing_flow" "recipeRevision" "euv-logic-recipe-r1"
   "productFamily" "euv-logic-wafer"
   "technologyNodeNm" 3 "waferDiameterMm" 300 "numericalAperture" 0.33 "sourcePowerW" 250
   "recipe" [{"stepId" "step-010-wafer-prep" "sequence" 10 "automationMode" "human-gated"}
             {"stepId" "step-020-euv-exposure" "sequence" 20 "automationMode" "closed-loop"}
             {"stepId" "step-030-develop" "sequence" 30 "automationMode" "closed-loop"}
             {"stepId" "step-040-etch" "sequence" 40 "automationMode" "closed-loop"}
             {"stepId" "step-050-metrology-inspection" "sequence" 50 "automationMode" "human-gated"}]
   "telemetryBinding" [{"tag" "EUV.SCANNER.DOSE_MJ_CM2" "source" "plc" "sampleRateHz" 1 "retention" "7y"}]
   "cadCamHandoff" {"format" "gdsii" "supplierExchangeFormat" "gdsii"}
   "supplierDid" "did:web:tsukuru.etzhayyim.com:euv-supplier-example"
   "ehsInterlocks" ["EUV.SOURCE.INTERLOCK"]
   "approvals" [{"role" "manufacturing-owner" "required" true "did" "did:web:tsukuru.etzhayyim.com:operator-example"}
                {"role" "quality-owner" "required" true "did" "did:web:tsukuru.etzhayyim.com:quality-example"}]})

(deftest test-validate-run-package-euv-green-is-ready
  (let [out (agent/validate-run-package "euv" euv-run-package-green)]
    (is (= "ready" (get out "status")))
    (is (empty? (get out "blockers")))))

(deftest test-validate-run-package-euv-missing-numeric-param-is-blocked
  (let [blocked (dissoc euv-run-package-green "numericalAperture")
        out (agent/validate-run-package "euv" blocked)]
    (is (= "blocked" (get out "status")))
    (is (some #{"numerical-aperture-numeric"} (get out "blockers")))))

(deftest test-validate-run-package-unsupported-vertical-is-honest
  (let [out (agent/validate-run-package "widget" {})]
    (is (= "blocked" (get out "status")))
    (is (some #{"unsupported-vertical"} (get out "blockers")))))
