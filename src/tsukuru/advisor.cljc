(ns tsukuru.advisor
  "The advising half of tsukuru 作 — and it is deliberately thin, because
  the decisions it would otherwise duplicate already exist.

  `tsukuru.kotoba.agent` has held this actor's domain logic since R0: the
  spec→capability match, the BTO/MTO/CTO order state machine, the QC
  verdict, the settlement split. Phase 2 of ADR-2800003200 puts tsukuru
  on the shared marketplace ref; it does not rewrite what tsukuru
  decides. So this namespace ADAPTS those functions into the
  `marketplace.edge/run` proposal shape and adds nothing of its own.

  ## Where confidence comes from

  Not a constant. `agent/handle-discover` scores a factory's declared
  capabilities against the spec by token overlap, and this uses that
  score: a factory whose capabilities the spec never mentions yields low
  confidence, which the governor turns into an escalation. That is the
  honest reading — 'nothing in this factory's declared capabilities
  matches what was asked for' is exactly when a person should look.

  A high score is not a claim that the factory can do the work. It is a
  claim that the registry says it might, which is why the confidence
  ceiling here is 0.9 and never 1.0.

  ## G2 eligibility, mapped rather than invented

  `agent/create-production-order` requires the buyer to hold an active
  Adherent SBT. On the marketplace ref the equivalent fact already
  exists: `-marketplace-order` wrote an order whose buyer is this member.
  So the SBT registry handed to the agent is DERIVED from that order —
  the member is eligible exactly when the marketplace already admitted
  them as the buyer of the order being manufactured against.

  This mapping is written down rather than assumed because it is a real
  judgement: it says marketplace buyer-hood is the SBT for this purpose.
  If the two ever need to differ, this is the line to change, and the
  governor's `:buyer-not-principal` check is unaffected either way."
  (:require [clojure.string :as str]
            [tsukuru.kotoba.agent :as agent]
            [tsukuru.store :as store]))

(def ^:private confidence-ceiling 0.9)
(def ^:private confidence-unmatched 0.4)

(defn- spec-match-confidence
  "How well the spec matches the factory's DECLARED capabilities.

  Runs the same `handle-discover` the actor has always used, with one
  factory in the list, and reads whether it survived the capability
  filter. Absent factory or absent spec -> unmatched, which escalates."
  [factory spec]
  (if (or (nil? factory) (str/blank? (str spec)))
    confidence-unmatched
    (let [state {"spec" spec "factories" [(agent/factory-datom->state factory)]}
          cands (get (agent/handle-discover state) "candidates" [])
          caps (get (first cands) "capabilities" [])
          spec-l (str/lower-case (str spec))
          hit? (some (fn [c] (some #(str/includes? spec-l %)
                                   (re-seq #"[a-z0-9]+" (str/lower-case (str c)))))
                     caps)]
      (if hit? confidence-ceiling confidence-unmatched))))

(defn- create-proposal [st request]
  (let [{:keys [po-id order-id buyer-did factory-did mode spec]} request
        factory (store/factory-record st factory-did)
        order (store/order-record st order-id)
        ;; G2 eligibility derived from marketplace buyer-hood — see ns docstring.
        sbt {buyer-did {"active" (= buyer-did (:order/buyer order))}}
        built (agent/create-production-order buyer-did factory-did mode spec sbt)]
    {:op :create-production-order
     :value (assoc built "id" po-id "orderId" order-id)
     :summary (str "production order " po-id " (" mode ") for " order-id
                   " -> " factory-did)
     :cites (vec (remove nil? [(when factory (str "factory:" factory-did))
                               (when order (str "order:" order-id))
                               (when (store/seller-credential st factory-did)
                                 (str "credential:" factory-did))]))
     :confidence (spec-match-confidence factory spec)}))

(defn- progress-proposal [st request]
  (let [po (store/production-order-record st (:po-id request))
        advanced (agent/advance-order (or po {}))]
    {:op :record-progress
     :value {"id" (:po-id request)
             "stage" (get advanced "state")
             "blocked" (get advanced "blocked")
             "note" (:note request)
             "order" advanced}
     :summary (str "production order " (:po-id request) " -> " (get advanced "state"))
     :cites [(str "production-order:" (:po-id request))]
     ;; Advancing a stage is a mechanical step over a state machine the
     ;; governor has already gated on compliance; there is nothing here
     ;; for a model to be uncertain about.
     :confidence confidence-ceiling}))

(defn- qc-proposal [st request]
  (let [po (store/production-order-record st (:po-id request))
        state {"order" (or po {})
               "defects" (:defects request [])
               "reworkable" (:reworkable? request true)
               "inspectorDid" (:inspector-did request)
               "now" (:now request)}
        out (agent/handle-qc state)]
    {:op :record-qc
     :value {"id" (:po-id request)
             "result" (get-in out ["quality" "result"])
             "defects" (get-in out ["quality" "defects"])
             "inspectorDid" (get-in out ["quality" "inspectorDid"])
             "order" (get out "order")}
     :summary (str "inspection of " (:po-id request) " -> "
                   (get-in out ["quality" "result"]))
     :cites [(str "production-order:" (:po-id request))]
     :confidence confidence-ceiling}))

(defn- halt-proposal [st request]
  (let [po (store/production-order-record st (:po-id request))]
    {:op :halt-order
     :value (assoc (or po {}) "id" (:po-id request)
                   "state" "quarantine"
                   "haltReason" (:reason request))
     :summary (str "halt " (:po-id request) ": " (:reason request))
     :cites [(str "production-order:" (:po-id request))]
     :confidence confidence-ceiling}))

(defn -advise
  "Propose. Deciding is the governor's job; every op here is reversible
  until it commits."
  [st request]
  (case (:op request)
    :create-production-order (create-proposal st request)
    :record-progress (progress-proposal st request)
    :record-qc (qc-proposal st request)
    :halt-order (halt-proposal st request)
    ;; An unknown op still produces a proposal, with no value and no
    ;; confidence, so the governor's `:op-out-of-scope` check is what
    ;; refuses it rather than an exception here. One refusal path, one
    ;; place to read it.
    {:op (:op request) :value nil :summary "unknown op" :cites [] :confidence 0.0}))
