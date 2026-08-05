(ns tsukuru.governor-test
  "What tsukuru REFUSES, and why each refusal is structural rather than
  advisory (ADR-2800003200 Phase 2).

  The chain these tests fix in place:

    onboarding writes a :credential  ->  tsukuru can order from that factory
    onboarding writes nothing        ->  tsukuru cannot, at any confidence,
                                         at any phase, with any consent

  No HTTP call is involved in either direction. The credential is a
  document in the same ref, and 'read it' means 'join'."
  (:require [clojure.test :refer [deftest is testing]]
            [tsukuru.advisor :as advisor]
            [tsukuru.governor :as governor]
            [tsukuru.phase :as phase]
            [tsukuru.store :as store]))

(def consented-did (:factory/did store/demo-factory))
(def buyer "did:web:member.example.etzhayyim.com")

(defn- request [& {:as over}]
  (merge {:op :create-production-order
          :ref "po-1" :po-id "po-1"
          :order-id "ord-1"
          :buyer-did buyer
          :factory-did consented-did
          :mode "bto"
          :spec "cnc-5axis aluminium bracket, 500 units"
          :consent-ref "sig:member-signed-1"}
         over))

(defn- govern
  ([req] (govern (store/mem-store) req))
  ([st req]
   (let [proposal (advisor/-advise st req)]
     (assoc (governor/check req {:phase 3} proposal st) :proposal proposal))))

(defn- rules [verdict] (set (map :rule (:violations verdict))))

;; ───────────────────────── the happy path ─────────────────────────

(deftest consented-credentialed-factory-passes-every-hard-check
  (let [v (govern (request))]
    (is (empty? (:violations v))
        "a consented, credentialed factory with a real marketplace order clears all nine")
    (is (false? (:hard? v)))
    (is (>= (:confidence v) governor/confidence-floor)
        "the spec names a capability the factory declares, so confidence is above the floor")))

;; ───────────────────────── the consent boundary ─────────────────────────

(deftest research-candidates-are-never-orderable
  (testing "a candidate: id from ADR-2800003200 Phase 1 cannot be dispatched to"
    (let [v (govern (request :factory-did "candidate:manufacturer-directory/jp/fanuc-corporation"))]
      (is (contains? (rules v) :factory-not-consented))
      (is (true? (:hard? v))
          "hard, so no human approval and no phase setting can let it through")))
  (testing "the refusal is about the ID SHAPE, not about being unknown"
    ;; Even if someone had put the candidate in the factory directory, the
    ;; prefix check fires first -- which is why put-factory! also refuses it.
    (let [st (store/mem-store (assoc-in (store/demo-data)
                                        [:factories "candidate:x/y"]
                                        {:factory/did "candidate:x/y"}))
          v (govern st (request :factory-did "candidate:x/y"))]
      (is (contains? (rules v) :factory-not-consented)))))

(deftest put-factory-refuses-unconsented-ids
  (let [st (store/mem-store)
        res (store/put-factory! st {:factory/did "candidate:manufacturer-directory/jp/fanuc-corporation"})]
    (is (= "factory-not-consented" (:error res)))
    (is (nil? (store/factory-record st "candidate:manufacturer-directory/jp/fanuc-corporation"))
        "and nothing was written")))

;; ───────────────────── the cross-actor join ─────────────────────

(deftest a-factory-without-an-onboarding-credential-cannot-receive-work
  (let [st (store/mem-store (update (store/demo-data) :credentials dissoc consented-did))
        v (govern st (request))]
    (is (contains? (rules v) :seller-credential-missing)
        "the credential is written by -marketplace-onboarding only after a human approved it")
    (is (true? (:hard? v)))))

(deftest the-credential-is-looked-up-by-seller-id-not-by-did
  (testing "a factory onboarded under a marketplace seller id still joins"
    (let [seller "merchant.riverside"
          data (-> (store/demo-data)
                   (assoc-in [:factories consented-did :factory/seller-id] seller)
                   (update :credentials dissoc consented-did)
                   (assoc-in [:credentials seller] {:seller/id seller :seller/status :active}))
          v (govern (store/mem-store data) (request))]
      (is (empty? (:violations v))
          "onboarding issues credentials against :seller/id; assuming it equals the
           factory DID would make this check unsatisfiable for every real seller")))
  (testing "and a wrong seller id is still refused"
    (let [data (assoc-in (store/demo-data) [:factories consented-did :factory/seller-id]
                         "merchant.someone-else")
          v (govern (store/mem-store data) (request))]
      (is (contains? (rules v) :seller-credential-missing)))))

(deftest a-suspended-credential-stops-new-work
  (let [st (store/mem-store (assoc-in (store/demo-data)
                                      [:credentials consented-did :seller/status] :suspended))]
    (is (contains? (rules (govern st (request))) :seller-credential-inactive))))

(deftest the-marketplace-order-must-exist
  (let [v (govern (request :order-id "ord-does-not-exist"))]
    (is (contains? (rules v) :order-unknown)
        "read from -marketplace-order's output, never from the proposal")))

;; ───────────────────────── G14 / G16 ─────────────────────────

(deftest tsukuru-never-becomes-the-buyer
  (is (contains? (rules (govern (request :buyer-did governor/actor-did))) :buyer-not-principal))
  (is (contains? (rules (govern (request :buyer-did ""))) :buyer-not-principal)))

(deftest unsupported-fulfillment-modes-are-refused
  (is (contains? (rules (govern (request :mode "spot"))) :mode-unsupported))
  (doseq [m ["bto" "mto" "cto"]]
    (is (not (contains? (rules (govern (request :mode m))) :mode-unsupported)))))

(deftest an-order-cannot-advance-until-compliance-passed
  (testing "pending compliance stops the advance (G16, no auto-pass)"
    (let [st (store/mem-store (assoc-in (store/demo-data) [:production-orders "po-1"]
                                        {"id" "po-1" "state" "placed" "complianceState" "pending"}))
          v (govern st {:op :record-progress :ref "po-1" :po-id "po-1"})]
      (is (contains? (rules v) :compliance-not-passed))))
  (testing "an absent order is also refused, not treated as clear"
    (let [v (govern {:op :record-progress :ref "po-x" :po-id "po-x"})]
      (is (contains? (rules v) :compliance-not-passed))))
  (testing "passed compliance clears it"
    (let [st (store/mem-store (assoc-in (store/demo-data) [:production-orders "po-1"]
                                        {"id" "po-1" "state" "placed" "complianceState" "passed"}))
          v (govern st {:op :record-progress :ref "po-1" :po-id "po-1"})]
      (is (empty? (:violations v))))))

(deftest claims-that-goods-already-shipped-are-out-of-scope
  (is (contains? (rules (govern (request :shipped? true))) :op-out-of-scope))
  (is (contains? (rules (govern (request :stage "shipped"))) :op-out-of-scope)))

(deftest ops-outside-the-allowlist-are-refused
  (is (contains? (rules (govern (request :op :wire-funds))) :op-out-of-scope)))

;; ───────────────────── escalation, not commit ─────────────────────

(deftest placing-an-order-without-member-consent-escalates
  (let [v (govern (request :consent-ref nil))]
    (is (empty? (:violations v)) "not a violation -- it is a decision for a person")
    (is (true? (:high-stakes? v)))
    (is (= :escalate (phase/verdict->disposition v)))))

(deftest an-order-never-auto-commits-at-any-phase
  (testing "even governor-clean, even consented, even at the most permissive phase"
    (let [v (govern (request))
          base (phase/verdict->disposition v)]
      (is (= :commit base) "the governor is satisfied")
      (is (= {:disposition :escalate :reason :phase-approval}
             (phase/gate 3 {:op :create-production-order} base))
          "and the phase table still sends it to a human -- there is no rollback
           for a batch a factory has already started"))))

(deftest a-failed-inspection-reaches-a-person
  (let [st (store/mem-store (assoc-in (store/demo-data) [:production-orders "po-1"]
                                      {"id" "po-1" "state" "qc" "complianceState" "passed"}))
        v (govern st {:op :record-qc :ref "po-1" :po-id "po-1" :qc-result "fail"})]
    (is (true? (:high-stakes? v)))
    (is (= :escalate (phase/verdict->disposition v)))))

(deftest halting-is-the-safe-direction
  (testing "a halt may auto-commit at every phase above 0"
    (doseq [p [1 2 3]]
      (is (= {:disposition :commit :reason nil}
             (phase/gate p {:op :halt-order} :commit))
          (str "phase " p))))
  (testing "phase 0 writes nothing at all"
    (is (= :hold (:disposition (phase/gate 0 {:op :halt-order} :commit))))))

;; ───────────────────── confidence means something ─────────────────────

(deftest a-spec-matching-no-declared-capability-escalates
  (let [v (govern (request :spec "hand-blown borosilicate glassware"))]
    (is (< (:confidence v) governor/confidence-floor)
        "nothing in this factory's declared capabilities matches what was asked for")
    (is (= :escalate (phase/verdict->disposition v)))))
