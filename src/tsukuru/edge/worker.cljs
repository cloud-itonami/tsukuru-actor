(ns tsukuru.edge.worker
  "tsukuru 作's Worker — B2B factory-direct ordering on the shared
  marketplace ref (ADR-2800003200 Phase 2).

  The host layer is `marketplace.edge`, written once and used by all
  eight actors now. What lives here is routes and judgement, which is
  the whole shape of the split: prefetch/run/flush bracketing, ordinal
  generation, fail-closed-without-a-seed and the two ledger routes are
  not this repo's problem.

  ## The chain this actor closes, without an HTTP call

    -marketplace-onboarding  a human approves a seller  -> :credential
    -marketplace-order       a buyer places an order    -> :order
    tsukuru (here)           reads BOTH by join, refuses without either,
                             and writes :production-order
    -marketplace-settlement  reads that production order

  There is no client for onboarding in this repo, and there will not be
  one. The credential is a document in the same ref; reading it is a
  query, not a call. That is the entire argument for one ref, and the
  cost — one writer lease per commit, throughput bounded by CAS
  contention rather than capacity — is the one CLAUDE.md names.

  ## What this Worker refuses to become

  It does not manufacture, ship, or pay. `/production-orders` places an
  order and `/progress` records what a factory reported; neither claims
  the actor observed anything. Settlement stops at an INTENT
  (`tsukuru.kotoba.agent/build-settlement-intent`) because broadcasting
  needs a member signature this host does not hold — the seed it reads
  is the actor's own graph identity, not anybody's spending key."
  (:require [marketplace.edge :as edge]
            [tsukuru.advisor :as advisor]
            [tsukuru.governor :as governor]
            [tsukuru.kotoba.agent :as agent]
            [tsukuru.phase :as phase]
            [tsukuru.store :as store]))

(def ^:private ops
  {:advise      advisor/-advise
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal _req]
                  (store/commit-record! st {:op (:op proposal) :value (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(defn- ctx [body]
  {:actor-id "tsukuru-edge"
   :phase (get body "phase" phase/default-phase)
   :now (get body "now" "2026-08-05T00:00:00Z")})

(defn- run [client wants body request]
  (edge/with-store
    {:client client :wants wants :store-fn store/kotobase-store}
    (fn [st]
      (edge/outcome (:ref request) (edge/run ops st (ctx body) request)))))

;; ───────────────────────── operator input ─────────────────────────

(defn- register-factory
  "Register a CONSENTED factory. Not an actor pass: this is operator input,
  and the one rule it carries is a hard refusal rather than a judgement —
  a `candidate:manufacturer-directory/...` id from the research plane may
  not enter the trading plane (see `tsukuru.store/put-factory!`)."
  [client body]
  (let [did (get body "factory-did")]
    (edge/with-store
      {:client client :wants {:factory [did]} :store-fn store/kotobase-store}
      (fn [st]
        (let [res (store/put-factory!
                   st {:factory/did did
                       :factory/display-name (get body "display-name")
                       :factory/country (get body "country")
                       :factory/isic (get body "isic")
                       ;; Which SELLER this factory is to the rest of the
                       ;; marketplace — the key onboarding issued its
                       ;; credential against. Defaults to the DID.
                       :factory/seller-id (get body "seller-id" did)
                       :factory/capabilities (vec (get body "capabilities" []))
                       :factory/fulfillment-modes (vec (get body "fulfillment-modes" []))
                       :factory/labor-provenance (get body "labor-provenance")})]
          (if (:error res)
            {:ref did :disposition "hold"
             :violations [{:rule (:error res) :detail (:detail res)}]}
            {:ref did :disposition "commit" :violations []
             :capabilities (vec (get body "capabilities" []))}))))))

;; ───────────────────────── routes ─────────────────────────

(defn- gated [request env f]
  (if-not (edge/authorised? request env)
    (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
    (-> (.json request) (.then #(f (js->clj %))) (.then #(edge/json % 200)))))

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/factories"))
    (gated request env #(register-factory client %))

    (and (= method "POST") (= path "/production-orders"))
    (gated request env
           (fn [b]
             (let [po (get b "po-id")]
               (run client
                    ;; Exactly the documents this request NAMES: the factory,
                    ;; its credential (written by onboarding), the marketplace
                    ;; order (written by the order actor), and the production
                    ;; order id being claimed.
                    {:factory [(get b "factory-did")]
                     ;; The credential is keyed by SELLER id, which need not equal
                     ;; the factory DID -- prefetch both so the governor's join
                     ;; finds it either way.
                     :credential [(get b "factory-did") (get b "seller-id")]
                     :order [(get b "order-id")]
                     :production-order [po]}
                    b
                    {:op :create-production-order
                     :ref po :po-id po
                     :order-id (get b "order-id")
                     :buyer-did (get b "buyer-did")
                     :factory-did (get b "factory-did")
                     :mode (get b "mode")
                     :spec (get b "spec")
                     :consent-ref (get b "consent-ref")}))))

    (and (= method "POST") (= path "/progress"))
    (gated request env
           (fn [b]
             (let [po (get b "po-id")]
               (run client {:production-order [po] :progress [po]} b
                    {:op :record-progress :ref po :po-id po
                     :stage (get b "stage") :note (get b "note")}))))

    (and (= method "POST") (= path "/qc"))
    (gated request env
           (fn [b]
             (let [po (get b "po-id")]
               (run client {:production-order [po] :qc [po]} b
                    {:op :record-qc :ref po :po-id po
                     :defects (vec (get b "defects" []))
                     :reworkable? (get b "reworkable" true)
                     :inspector-did (get b "inspector-did")
                     :qc-result (get b "expect-result")
                     :now (get b "now")}))))

    (and (= method "POST") (= path "/halt"))
    (gated request env
           (fn [b]
             (let [po (get b "po-id")]
               (run client {:production-order [po]} b
                    {:op :halt-order :ref po :po-id po :reason (get b "reason")}))))

    ;; Settlement stops at an intent. Pure arithmetic over the tithe split,
    ;; no store write and no broadcast: executing needs a member signature
    ;; (G15) that this host does not hold and must never hold.
    (and (= method "POST") (= path "/settlement-intent"))
    (gated request env
           (fn [b]
             (agent/build-settlement-intent (get b "gross-minor" 0)
                                            (get b "buyer-sig-ref"))))

    (and (= method "GET") (= path "/production-orders"))
    (-> (edge/read-all client :production-order)
        (.then (fn [pos]
                 (edge/json {:production-orders
                             (mapv (fn [p] {:id (get p "id")
                                            :order (get p "orderId")
                                            :factory (get p "factoryDid")
                                            :mode (get p "mode")
                                            :state (get p "state")
                                            :compliance (get p "complianceState")})
                                   pos)}
                            200))))

    (and (= method "GET") (= path "/factories"))
    (-> (edge/read-all client :factory)
        (.then (fn [fs]
                 (edge/json {:factories (mapv (fn [f] {:did (:factory/did f)
                                                       :name (:factory/display-name f)
                                                       :country (:factory/country f)
                                                       :isic (:factory/isic f)})
                                              fs)}
                            200))))

    ;; /escalations and /ledger, implemented once in marketplace.edge. Every
    ;; production order escalates rather than committing on a machine's
    ;; say-so; without a way to READ those, that gate is a black hole.
    :else (edge/ledger-routes client request env method path :tsukuru)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-tsukuru" request env routes))}))
