(ns tsukuru.store
  "SSoT for tsukuru 作 on the shared marketplace ref — B2B factory-direct
  ordering, joined to the actors that already live there.

  ## Why this actor writes into someone else's ref

  ADR-2800003200 Phase 2. CLAUDE.md's kotobase rule is that a Datalog
  join reaches exactly one ref, so anything split across refs can never
  be joined again. A production order wants joining to three things this
  actor does not own:

    :credential  the seller credential `-marketplace-onboarding` issued
                 after a HUMAN approved it. tsukuru reads it and refuses
                 without it. This is the whole cross-actor chain, and it
                 is a JOIN — no HTTP call to onboarding exists.
    :order       the marketplace order `-marketplace-order` wrote. A
                 production order is built FOR one; tsukuru does not get
                 to invent the order it is manufacturing against.
    :seller      (via the credential) who the factory is to the rest of
                 the marketplace.

  So this actor uses `marketplace.edge/default-db` like the other seven
  and shards nothing. The cost is the one the rule names: one ref means
  one writer lease per commit, so throughput is bounded by CAS
  contention. The answer to that is a batching single writer, not a
  shard.

  ## The consent boundary, enforced at the ref

  ADR-2800003200 Phase 1 put 2,119 public-directory manufacturers on the
  superproject's datom plane under `:source/dataset \"tsukuru-candidates\"`.
  **None of them may enter this ref.** They never consented to anything;
  a research directory row that reaches the shared marketplace graph is
  indistinguishable there from an onboarded supplier.

  `put-factory!` therefore refuses any id that is not a `did:web:` —
  which is exactly the id shape candidates.edn deliberately does not
  use. The research plane and the trading plane meet nowhere but in a
  human's decision to onboard.

  Directories, keyed by STRING ids:

    factories          consented manufacturers. did:web only.
    production-orders  BTO / MTO / CTO orders — the thing this actor owns.
    progress           stage updates per production order, APPENDED.
    qc                 the latest inspection verdict per production order.

  The ledger stays append-only."
  (:require [clojure.string :as str]
            [marketplace.persist :as persist]))

(defprotocol Store
  (factory-record [s factory-did] "A CONSENTED factory, or nil.")
  (all-factories [s])
  (seller-credential [s seller-id]
    "Written by -marketplace-onboarding after a human approved it. Read-only here:
     this actor never issues an identity.")
  (order-record [s order-id]
    "The marketplace order from -marketplace-order, or nil. Read-only here.")
  (production-order-record [s po-id])
  (all-production-orders [s])
  (progress-for [s po-id] "Every stage update so far, [] when none.")
  (qc-for [s po-id] "The latest inspection verdict, or nil.")
  (ledger [s])
  (production-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (durable? [s] "False for the test-only memory backend."))

;; ----------------------------- demo data -----------------------------

(def demo-factory
  {:factory/did "did:web:tsukuru.etzhayyim.com/factory/jp-precision-cnc"
   :factory/seller-id "did:web:tsukuru.etzhayyim.com/factory/jp-precision-cnc"
   :factory/display-name "JP Precision CNC"
   :factory/country "JP"
   :factory/isic "C25"
   :factory/capabilities ["cnc-5axis" "precision-machining"]
   :factory/fulfillment-modes ["bto" "mto"]
   :factory/labor-provenance "disclosed"})

(defn demo-data
  "Fixtures covering the happy path and each hard check.

    the factory above          consented, credentialed  -> orderable
    candidate:...              research directory        -> never orderable
    a did:web with NO credential                         -> refused"
  []
  {:factories {(:factory/did demo-factory) demo-factory}
   :credentials {(:factory/did demo-factory)
                 {:seller/id (:factory/did demo-factory)
                  :seller/status :active
                  :seller/issued-at "2026-08-01T00:00:00Z"}}
   :orders {"ord-1" {:order/id "ord-1" :order/buyer "did:web:member.example.etzhayyim.com"}}
   :production-orders {}
   :progress {}
   :qc {}
   :ledger []
   :production-log []})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (factory-record [_ did] (get-in @a [:factories did]))
  (all-factories [_] (sort-by :factory/did (vals (:factories @a))))
  (seller-credential [_ id] (get-in @a [:credentials id]))
  (order-record [_ id] (get-in @a [:orders id]))
  (production-order-record [_ id] (get-in @a [:production-orders id]))
  (all-production-orders [_] (sort-by #(get % "id") (vals (:production-orders @a))))
  (progress-for [_ id] (get-in @a [:progress id] []))
  (qc-for [_ id] (get-in @a [:qc id]))
  (ledger [_] (:ledger @a))
  (production-log [_] (:production-log @a))
  (durable? [_] false)
  (commit-record! [_ record]
    (swap! a update :production-log conj record)
    (let [{:keys [op value]} record
          id (get value "id")]
      (case op
        :create-production-order (swap! a assoc-in [:production-orders id] value)
        ;; Progress is APPENDED, never replaced. Overwriting would hide a
        ;; stage the factory actually reported, and the whole point of a
        ;; progress trail is that it shows what happened, not where it ended.
        :record-progress (swap! a update-in [:progress id] (fnil conj []) value)
        :record-qc (do (swap! a assoc-in [:qc id] value)
                       (when-let [o (get value "order")]
                         (swap! a assoc-in [:production-orders id] o)))
        :halt-order (swap! a assoc-in [:production-orders id] value)
        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn mem-store
  ([] (mem-store (demo-data)))
  ([data] (->MemStore (atom data))))

;; ----------------------------- durable store -----------------------------

(defrecord KotobaseStore [st seed]
  Store
  (factory-record [_ did] (persist/get-doc (persist/ctx st :factory :factory/did) did))
  (all-factories [_] (persist/all-docs (persist/ctx st :factory :factory/did)))
  ;; The two reads that make this a marketplace actor rather than a silo.
  ;; Both are written by OTHER actors into the same ref; neither is
  ;; fetched over HTTP, and neither may be written here.
  (seller-credential [_ id] (persist/get-doc (persist/ctx st :credential :seller/id) id))
  (order-record [_ id] (persist/get-doc (persist/ctx st :order :order/id) id))
  (production-order-record [_ id] (persist/get-doc (persist/ctx st :production-order :po/id) id))
  (all-production-orders [_] (persist/all-docs (persist/ctx st :production-order :po/id)))
  (progress-for [_ id] (:progress/items (persist/get-doc (persist/ctx st :progress :po/id) id) []))
  (qc-for [_ id] (persist/get-doc (persist/ctx st :qc :po/id) id))
  (durable? [_] (not (:persist/memory? st)))
  (ledger [_] (persist/read-events (persist/stream-ctx st :ledger)))
  (production-log [_] (persist/read-events (persist/stream-ctx st :production-log)))
  (commit-record! [this record]
    (persist/append-event! (persist/stream-ctx st :production-log) seed record)
    (let [{:keys [op value]} record
          id (get value "id")
          poctx (persist/ctx st :production-order :po/id)]
      (case op
        :create-production-order (persist/put-doc! poctx (assoc value :po/id id))

        :record-progress
        (persist/put-doc! (persist/ctx st :progress :po/id)
                          {:po/id id
                           :progress/items (conj (vec (progress-for this id)) value)})

        :record-qc
        (do (persist/put-doc! (persist/ctx st :qc :po/id) (assoc value :po/id id))
            (when-let [o (get value "order")]
              (persist/put-doc! poctx (assoc o :po/id id))))

        :halt-order (persist/put-doc! poctx (assoc value :po/id id))
        nil))
    record)
  (append-ledger! [_ fact] (persist/append-event! (persist/stream-ctx st :ledger) seed fact)))

(defn kotobase-store
  "A durable store over a HOST-INJECTED database API. Throws when the host
  has not wired one, per `:policy/fail-closed-without-host-injection`."
  [{:keys [db-api seq-fn]}]
  (->KotobaseStore (persist/store {:db-api db-api :actor "tsukuru"})
                   (or seq-fn (let [n (atom 0)] #(swap! n inc)))))

;; ----------------------------- operator input -----------------------------

(def consented-did-prefix
  "The only id shape that may enter the trading plane.

  `kotoba/candidates.edn` deliberately uses `candidate:manufacturer-directory/...`
  for its 2,119 public-directory companies precisely so that an unconsented
  row cannot be mistaken for an onboarded one. Enforcing the prefix here makes
  that a property of the REF, not just of the file."
  "did:web:")

(defn consented-did? [did]
  (and (string? did) (str/starts-with? did consented-did-prefix)))

(defn put-factory!
  "Register a CONSENTED factory. Operator input.

  Refuses anything that is not a `did:web:` — a research-directory candidate
  reaching this ref would be indistinguishable from an onboarded supplier, and
  this actor's whole G10 posture is that it never claims a relationship that
  does not exist. Returns `{:error ..}` rather than throwing so the host can
  answer 422 with the reason."
  [s factory]
  (let [did (:factory/did factory)]
    (if-not (consented-did? did)
      {:error "factory-not-consented"
       :detail (str "factory/did must start with " consented-did-prefix
                    " (got " (pr-str did) "). Public-directory candidates are"
                    " research references and never enter the marketplace ref.")}
      (do (if (instance? KotobaseStore s)
            (persist/put-doc! (persist/ctx (:st s) :factory :factory/did) factory)
            (swap! (:a s) assoc-in [:factories did] factory))
          factory))))
