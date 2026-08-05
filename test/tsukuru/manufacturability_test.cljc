(ns tsukuru.manufacturability-test
  "ADR-2800003200 Phase 3's completion condition, stated as tests: run a real
  production order through `:cfd` AND `:fem` and see qualified and
  not-qualified BOTH actually happen. A gate that only ever returns one
  answer is theatre."
  (:require [clojure.test :refer [deftest is testing]]
            [tsukuru.governor :as governor]
            [tsukuru.manufacturability :as mfg]
            [tsukuru.phase :as phase]
            [tsukuru.store :as store]))

;; ── operator-supplied V&V evidence ──────────────────────────────────────
;; Every one of these is INPUT. `tsukuru.manufacturability` fabricates no
;; check and defaults none to passing; a case with the evidence block
;; removed must come back :not-qualified, and the second test proves it.

(def passing-checks
  [{:check :analytic-benchmark :passed? true}
   {:check :conservation :passed? true}
   {:check :iterative-convergence :passed? true}
   {:check :grid-convergence :passed? true}])

(def evidence
  {:case-id "cae-po-1" :solver "cae.industrial/fem" :solver-version "37bbc49"
   :model-revision "rev-3" :input-id "in-1" :mesh-id "mesh-1"
   :executed-at "2026-08-05T00:00:00Z" :platform "jvm-21/aarch64"})

(defn- po
  "A production order carrying a declared engineering case."
  [kind kase & {:keys [checks ev scope]
                :or {checks passing-checks ev evidence
                     scope {:physics :linear-elasticity :dimension :1d}}}]
  {"id" "po-1" "state" "placed" "complianceState" "passed"
   "cae" {"kind" (name kind) "case" kase "scope" scope
          "checks" checks "evidence" ev}})

(def fem-case
  {:element :cantilever-beam :length-m 0.30 :width-m 0.02 :height-m 0.01
   :youngs-modulus-Pa 200e9 :load-N 500.0})

(def cfd-case
  {:flow-m3-s 1.2 :duct-diameter-m 0.4 :duct-length-m 20.0})

;; ───────────────────── both outcomes actually occur ─────────────────────

(deftest fem-with-full-vv-evidence-qualifies
  (let [a (mfg/assess (po :fem fem-case))]
    (is (true? (:declared? a)))
    (is (true? (:qualified? a)) "all five V&V categories have passing evidence")
    (is (= :verified-for-declared-scope (:status a)))
    (is (= :declared-scope-only (:claim a))
        "and the claim stays scoped -- never an industrial-accuracy claim")
    (is (some? (get-in a [:result :stress-Pa]))
        "a real number came out of cae.industrial/fem")))

(deftest cfd-with-full-vv-evidence-qualifies
  (let [a (mfg/assess (po :cfd cfd-case :ev (assoc evidence :solver "cae.industrial/cfd")))]
    (is (true? (:qualified? a)))
    (is (some? (get-in a [:result :pressure-drop-Pa])))))

(deftest the-same-case-without-vv-evidence-does-not-qualify
  (testing "missing evidence block"
    (let [a (mfg/assess (po :fem fem-case :ev nil))]
      (is (false? (:qualified? a)))
      (is (= :not-qualified (:status a)))
      (is (= :no-industrial-accuracy-claim (:claim a)))
      (is (some? (get-in a [:result :stress-Pa]))
          "the NUMBER is still there -- which is exactly why the number is not the gate")))
  (testing "missing one check category"
    (let [a (mfg/assess (po :fem fem-case
                            :checks (remove #(= :grid-convergence (:check %)) passing-checks)))]
      (is (false? (:qualified? a)))
      (is (= [:grid-convergence] (:missing-checks a)))))
  (testing "a check that ran and failed"
    (let [a (mfg/assess (po :fem fem-case
                            :checks (conj (vec (remove #(= :conservation (:check %)) passing-checks))
                                          {:check :conservation :passed? false})))]
      (is (false? (:qualified? a)))
      (is (= [:conservation] (:failed-checks a))))))

(deftest a-case-the-solver-refuses-is-recorded-not-thrown
  (let [a (mfg/assess (po :fem (assoc fem-case :length-m -1.0)))]
    (is (false? (:qualified? a)))
    (is (some? (:error a)) "cae.industrial is strict about non-physical input, and that
                            strictness is an answer to 'can this be built', not a crash")))

(deftest an-unsupported-kind-is-refused-rather-than-defaulted
  (let [a (mfg/assess (po :telepathy fem-case))]
    (is (false? (:qualified? a)))
    (is (= :unsupported-kind (:status a)))))

;; ───────────────────── absence of a claim ≠ a failed claim ─────────────────────

(deftest an-order-with-no-declared-case-is-not-assessed-and-not-penalised
  (let [a (mfg/assess {"id" "po-1" "state" "placed" "complianceState" "passed"})]
    (is (false? (:declared? a)))
    (is (= :not-assessed (:status a)))
    (is (= :no-claim-made (:claim a)))))

(deftest the-ledger-fact-distinguishes-never-assessed-from-passed
  (let [never (mfg/gate-fact {"id" "po-1"})
        passed (mfg/gate-fact (po :fem fem-case))
        failed (mfg/gate-fact (po :fem fem-case :ev nil))]
    (is (= [false :not-assessed] [(:declared? never) (:status never)]))
    (is (= [true true :verified-for-declared-scope]
           [(:declared? passed) (:qualified? passed) (:status passed)]))
    (is (= [true false :not-qualified]
           [(:declared? failed) (:qualified? failed) (:status failed)]))
    (is (not= never passed) "an order nobody checked must never read like one that passed")))

;; ───────────────────── the gate is advisory, in ONE direction ─────────────────────

(defn- govern-progress [order]
  (let [st (store/mem-store (assoc-in (store/demo-data) [:production-orders "po-1"] order))
        req {:op :record-progress :ref "po-1" :po-id "po-1"}]
    (governor/check req {:phase 3} {:confidence 0.9} st)))

(deftest a-not-qualified-case-stops-the-order-and-asks-a-person
  (let [v (govern-progress (po :fem fem-case :ev nil))]
    (is (true? (:high-stakes? v)))
    (is (= :manufacturability-not-qualified (:escalation-reason v)))
    (is (= :escalate (phase/verdict->disposition v)))
    (is (empty? (:violations v))
        "not a hard violation -- a person may still decide to proceed on a prototype")))

(deftest a-qualified-case-lowers-nothing
  (testing "it does not by itself make the order auto-commit"
    (let [v (govern-progress (po :fem fem-case))]
      (is (false? (:high-stakes? v)))
      (is (= :commit (phase/verdict->disposition v)))
      (is (true? (get-in v [:manufacturability :qualified?])))))
  (testing "and it cannot rescue an order that failed a HARD check"
    ;; Compliance (G16) is un-overridable; a passing stress calculation
    ;; must not touch it.
    (let [v (govern-progress (assoc (po :fem fem-case) "complianceState" "pending"))]
      (is (true? (:hard? v)))
      (is (contains? (set (map :rule (:violations v))) :compliance-not-passed))
      (is (= :hold (phase/verdict->disposition v))
          "qualified physics, still held -- the gate is advisory in one direction only"))))

(deftest an-unassessed-order-still-advances
  (let [v (govern-progress {"id" "po-1" "state" "placed" "complianceState" "passed"})]
    (is (false? (:high-stakes? v)))
    (is (= :commit (phase/verdict->disposition v))
        "most orders declare no engineering case, and that is not a failure")))
