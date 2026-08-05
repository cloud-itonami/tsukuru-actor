(ns tsukuru.governor
  "TsukuruGovernor — what stands between an LLM reading a spec and a real
  factory being told to build something.

  The advisor half of this actor is `tsukuru.kotoba.agent`, which matches
  specs to factories and drives an order state machine. None of that is
  authority. A production order commits money, a supplier relationship
  and physical material, so every one of the checks below is applied to
  the PROPOSAL by code that the advisor cannot influence.

  ## Nine HARD checks — permanent, un-overridable by any human approval

    1. Op out of scope        anything outside the closed allowlist, plus
                              any claim that goods ALREADY shipped. This
                              actor places and tracks orders; it never
                              reports having manufactured or delivered
                              one.
    2. Factory not consented  a `:factory/did` that is not a `did:web:`.
                              The 2,119 public-directory manufacturers
                              from ADR-2800003200 Phase 1 use
                              `candidate:manufacturer-directory/...`
                              precisely so this check can exist. A
                              research reference is not a supplier, and
                              dispatching work to one would be a claim
                              of a relationship that nobody agreed to.
    3. Factory unknown        consented shape, but no record in the ref.
    4. Credential missing     no seller credential for this factory.
                              **This is the cross-actor join**: the
                              credential is written by
                              `-marketplace-onboarding` only after a
                              HUMAN approved it, and tsukuru reads it out
                              of the shared ref. No HTTP call exists; if
                              onboarding never issued one, this actor
                              cannot order from that factory.
    5. Credential inactive    issued once, since suspended or revoked.
    6. Order unknown          the marketplace order being manufactured
                              against does not exist. Read from
                              `-marketplace-order`'s output, never from
                              the proposal — an actor that accepts the
                              order id it was handed can be told to
                              manufacture against anything.
    7. Buyer not principal    G14. The member is the purchasing
                              principal; tsukuru never becomes the buyer.
                              A missing buyer, or one equal to this
                              actor's own DID, is refused.
    8. Mode unsupported       BTO / MTO / CTO only.
    9. Compliance not passed  G16. An order may not advance past `placed`
                              until `tsukuru.kotoba.agent/handle-compliance`
                              says `passed`. That function never
                              auto-passes — an unresolved screening, an
                              unclassified product or a flagged dual-use
                              category all land in `pending`, and
                              `pending` is refused here. **A stub that
                              cannot reach yabai produces `pending`, not
                              `passed`**, so an unwired screening
                              integration fails closed.

  ## Three ESCALATE (soft) gates

    - Confidence below the floor.
    - Creating a production order WITHOUT a member-signed consent
      reference (G1). This is the load-bearing one: committing a member
      to a manufacturing order is exactly the move that must not happen
      on a machine's say-so, so it reaches a person unless the member
      already signed.
    - A QC verdict of `fail`. Quarantining a batch is a human call; the
      actor records the inspection and stops.

  A refusal here is not a bug report about the advisor. It is the design:
  the advisor is free to propose anything, and nothing it proposes
  reaches the ref without passing this."
  (:require [clojure.string :as str]
            [tsukuru.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  #{:create-production-order :record-progress :record-qc :halt-order})

(def fulfillment-modes #{"bto" "mto" "cto"})

(def actor-did
  "This actor's own identity. Present so check 7 can refuse an order in
  which tsukuru is named as the buyer — G14 is that the member is the
  purchasing principal, and an actor that can buy has become a
  counterparty rather than an intermediary."
  "did:web:tsukuru.etzhayyim.com")

(defn- v [rule detail] {:rule rule :detail detail})

(defn- shipped-claim?
  "Any assertion that manufacture or delivery has ALREADY happened.

  Scope exclusion, not a warning: this actor's honest position is that it
  places and tracks orders. A request that arrives already claiming the
  goods shipped is asking the ledger to record something nobody observed."
  [request]
  (boolean (or (:shipped? request)
               (some-> (:stage request) str/lower-case #{"shipped" "delivered"})
               (some-> (:claim request) str/lower-case
                       (as-> s (some #(str/includes? s %) ["already shipped" "already delivered"
                                                           "manufactured and shipped"]))))))

(defn- credential-violations
  "The cross-actor join, on the key onboarding actually issues against.

  A credential is issued to a SELLER, and `-marketplace-onboarding` keys it
  by `:seller/id` — the identity that repo admitted, which is not
  necessarily the DID this actor trades against. So a factory declares
  which seller it is (`:factory/seller-id`, defaulting to its own DID) and
  the lookup follows that.

  Assuming the two were always equal would have made this check
  unsatisfiable for any factory onboarded under a marketplace seller id,
  and an unsatisfiable check reads exactly like a working one until
  somebody tries to place a real order."
  [st factory-did]
  (let [factory (store/factory-record st factory-did)
        seller-id (or (:factory/seller-id factory) factory-did)
        cred (store/seller-credential st seller-id)]
    (cond
      (nil? cred)
      [(v :seller-credential-missing
          (str "no seller credential for " seller-id
               (when (not= seller-id factory-did) (str " (seller of " factory-did ")"))
               " — -marketplace-onboarding issues one only after a human approves;"
               " this actor reads it by join and cannot order without it"))]

      (not (contains? #{:active "active"} (:seller/status cred)))
      [(v :seller-credential-inactive
          (str "credential for " seller-id " is " (pr-str (:seller/status cred))))]

      :else [])))

(defn- factory-violations [st factory-did]
  (cond
    (not (store/consented-did? factory-did))
    [(v :factory-not-consented
        (str (pr-str factory-did) " is not a " store/consented-did-prefix
             " id. Public-directory candidates (ADR-2800003200 Phase 1) are research"
             " references — they consented to nothing and are never orderable."))]

    (nil? (store/factory-record st factory-did))
    [(v :factory-unknown (str factory-did " is not registered in this ref"))]

    :else (credential-violations st factory-did)))

(defn- order-violations [st order-id]
  (if (nil? (store/order-record st order-id))
    [(v :order-unknown
        (str (pr-str order-id) " names no marketplace order."
             " A production order is manufactured FOR an order that"
             " -marketplace-order wrote; this actor does not invent one."))]
    []))

(defn- principal-violations [buyer-did]
  (cond
    (str/blank? (str buyer-did))
    [(v :buyer-not-principal "no buyer DID — the member is the purchasing principal (G14)")]

    (= buyer-did actor-did)
    [(v :buyer-not-principal
        "tsukuru named as buyer — this actor never becomes the counterparty (G14)")]

    :else []))

(defn- compliance-violations
  "G16, read off the STORED order rather than the request.

  An order that has not cleared screening may not advance past `placed`.
  Reading the request's own compliance claim would let a caller assert
  its way past the gate."
  [st po-id]
  (let [po (store/production-order-record st po-id)
        state (get po "complianceState")]
    (if (= "passed" state)
      []
      [(v :compliance-not-passed
          (str "production order " po-id " has complianceState "
               (pr-str (or state "absent"))
               " — handle-compliance never auto-passes, so an unresolved"
               " screening stays 'pending' and stops here (G16)"))])))

(defn check
  "Govern one proposal. Returns
  `{:violations [..] :hard? bool :escalate? bool :high-stakes? bool :confidence n}`."
  [request _context proposal st]
  (let [op (:op request)
        confidence (or (:confidence proposal) 0.0)
        hard (cond-> []
               (not (contains? allowed-ops op))
               (conj (v :op-out-of-scope (str (pr-str op) " is outside the allowlist")))

               (shipped-claim? request)
               (conj (v :op-out-of-scope
                        "request claims goods already shipped — this actor places and tracks orders"))

               (= :create-production-order op)
               (into (concat (factory-violations st (:factory-did request))
                             (order-violations st (:order-id request))
                             (principal-violations (:buyer-did request))
                             (when-not (contains? fulfillment-modes (:mode request))
                               [(v :mode-unsupported
                                   (str (pr-str (:mode request)) " is not one of "
                                        (str/join "/" (sort fulfillment-modes))))])))

               (= :record-progress op)
               (into (compliance-violations st (:po-id request))))
        ;; Consent (G1) and a failed inspection are the two moves that must
        ;; not commit on a machine's say-so.
        high-stakes? (boolean (or (and (= :create-production-order op)
                                       (str/blank? (str (:consent-ref request))))
                                  (and (= :record-qc op)
                                       (= "fail" (:qc-result request)))))]
    {:violations hard
     :hard? (boolean (seq hard))
     :high-stakes? high-stakes?
     :escalate? (or high-stakes? (< confidence confidence-floor))
     :confidence confidence}))

(defn hold-fact
  "What goes on the ledger when the governor refuses.

  The rules AND their detail, because a ledger entry reading only
  `compliance-not-passed` sends the reader back to the source to find out
  which half was false."
  [request _context verdict]
  {:t :refused
   :op (:op request)
   :violations (mapv :rule (:violations verdict))
   :detail (mapv :detail (:violations verdict))})
