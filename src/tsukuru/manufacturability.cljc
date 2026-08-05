(ns tsukuru.manufacturability
  "Physics before a factory starts cutting metal — ADR-2800003200 Phase 3.

  Until now the only thing standing between a spec and a real production
  order was capability-token overlap: the factory's registry entry said
  `cnc-5axis`, the spec said `cnc-5axis`, and that was the whole
  engineering argument. That is a *procurement* check, not a
  manufacturability one.

  `kotoba-lang/kami-engine-cae-solver` already answers the physics
  question deterministically for six domains (`:cfd :fem :process
  :materials :emag :production-des`). This namespace calls it. It adds no
  solver, no correlation and no numbers of its own.

  ## The gate is a NUMBER and a QUALIFICATION, and only one of them is a
  ## reason to proceed

  `cae.solver/solve` will happily return a pressure drop or a tip
  deflection for any well-formed case. Numbers coming out is not the same
  as the model being fit to answer the question, so every assessment also
  runs `cae.vv/qualification-gate`, which fails closed unless ALL of

    :analytic-benchmark  :conservation  :iterative-convergence
    :grid-convergence    :traceability

  have passing evidence. That evidence is OPERATOR INPUT carried on the
  order. This namespace never manufactures a check, never defaults one to
  passing, and never treats an absent evidence block as a clean one — an
  order with no V&V evidence assesses as `:not-qualified`, exactly as
  `cae-solver`'s own contract says it must:

    'Missing V&V evidence defaults to not qualified; execution never
     implies an industrial release.'

  ## Advisory, in both directions

  ADR-2800003200 Phase 3: a NOT-qualified assessment is a reason to stop
  an order, and a qualified one is NOT a reason to dispatch it. So this
  namespace returns a verdict and `tsukuru.governor` turns only the
  negative into an escalation. Nothing here can lower another gate:
  consent (G1), member-principal (G14) and trade compliance (G16) are
  untouched by a passing stress calculation.

  Reading a qualified result as 'manufacturable' would be the same class
  of error ADR-2607203000 named — multiplying a large number by an
  unmeasured rate and calling the product a forecast."
  (:require [cae.industrial]                                ;; registers :cfd/:fem/... methods
            [cae.solver :as cae]
            [cae.vv :as vv]
            [clojure.string :as str]))

(def supported-kinds
  "The deterministic backends this gate will dispatch to. Deliberately the
  set `cae.industrial` already registers — a kind outside it is refused
  rather than silently defaulted, because `cae.solver/solve`'s `:default`
  method throws and a thrown gate reads as a broken actor rather than as
  an unanswered question."
  #{:cfd :fem :process :materials :emag :production-des})

(defn declared?
  "Does this order ask for a manufacturability assessment at all?

  Most orders will not, and that is not a failure — it is the absence of a
  claim. The distinction has to survive into the ledger, so an order that
  was never assessed can never be read later as one that passed."
  [po]
  (boolean (get po "cae")))

(defn- solve-safely
  "Run the solver, turning its own refusals into data.

  `cae.industrial` throws on a non-physical case (negative length, a
  combustion source missing its heating value). That is the library being
  strict and is a legitimate answer to 'can this be built' — so it becomes
  a recorded failure rather than a 500."
  [kase]
  (try
    {:result (cae/solve kase)}
    (catch #?(:clj Exception :cljs :default) e
      {:error (or #?(:clj (.getMessage e) :cljs (.-message e)) "solver refused the case")
       :data (ex-data e)})))

(defn assess
  "Assess one production order's declared engineering case.

  `po` is the stored production order; its `\"cae\"` block is operator
  input of the shape

      {\"kind\"     \"fem\"
       \"case\"     {...the backend's own parameter map...}
       \"scope\"    {...declared physics scope...}
       \"checks\"   [{:check :conservation :passed? true} ...]
       \"evidence\" {:case-id .. :solver .. :executed-at .. ...}}

  Returns

      {:declared? bool :kind kw :qualified? bool :status kw :claim kw
       :missing-checks [..] :failed-checks [..] :result {..} | :error str}

  `:qualified?` is true only when the V&V gate passed AND the solver
  produced a result. Either half alone is meaningless: an unqualified
  number is a guess, and a qualified case that would not solve is not an
  answer."
  [po]
  (if-not (declared? po)
    {:declared? false :qualified? false :status :not-assessed
     :claim :no-claim-made}
    (let [cae-block (get po "cae")
          kind (keyword (get cae-block "kind"))
          kase (get cae-block "case")
          gate (vv/qualification-gate
                {:scope (get cae-block "scope")
                 :checks (get cae-block "checks")
                 :evidence (get cae-block "evidence")})]
      (if-not (contains? supported-kinds kind)
        {:declared? true :kind kind :qualified? false :status :unsupported-kind
         :claim :no-industrial-accuracy-claim
         :error (str (pr-str kind) " is not one of "
                     (str/join "/" (sort (map name supported-kinds))))}
        (let [{:keys [result error data]} (solve-safely (assoc kase :solver {:kind kind}))]
          (cond-> {:declared? true
                   :kind kind
                   ;; BOTH halves. A passing V&V gate over a case the solver
                   ;; refused is not an assessment.
                   :qualified? (boolean (and (:passed? gate) result))
                   :status (:status gate)
                   :claim (:claim gate)
                   :missing-checks (vec (:missing-checks gate))
                   :failed-checks (mapv :check (:failed-checks gate))}
            result (assoc :result result)
            error (assoc :error error :error-data data)))))))

(defn gate-fact
  "What goes on the append-only ledger about this assessment.

  Written on EVERY path, including `:not-assessed`. An order that was
  never assessed and an order that passed must not look the same six
  months later when somebody asks why a batch was approved."
  [po]
  (let [a (assess po)]
    (cond-> {:t :manufacturability
             :declared? (:declared? a)
             :qualified? (:qualified? a)
             :status (:status a)
             :claim (:claim a)}
      (:kind a) (assoc :kind (:kind a))
      (seq (:missing-checks a)) (assoc :missing-checks (:missing-checks a))
      (seq (:failed-checks a)) (assoc :failed-checks (:failed-checks a))
      (:error a) (assoc :error (:error a)))))
