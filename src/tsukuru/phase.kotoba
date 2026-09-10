(ns tsukuru.phase
  "Phase 0->3 staged rollout for tsukuru 作.

    Phase 0  read-only            no writes, still governor-gated.
    Phase 1  assisted-ordering    orders may be placed, every write needs
                                  a human.
    Phase 2  assisted-tracking    adds progress and QC recording, still
                                  approval-gated.
    Phase 3  supervised auto      governor-clean, high-confidence
                                  progress/QC recording may auto-commit.

  ## `:create-production-order` is auto-eligible at NO phase

  Deliberately, and it is the difference between this actor and the
  warehouse one. Committing a member to a manufacturing order spends
  their money on physical material at a real supplier. There is no
  rollback for a batch that has been started. So the op stays out of
  every `:auto` set: at phase 3 a governor-clean order still escalates.

  The governor reaches the same conclusion by a different route (an
  order without a member-signed consent reference is high-stakes), and
  the redundancy is intentional. A future change that decides consent
  can be inferred would silently open auto-commit if the phase table
  were the only gate; a future change to the phase table would silently
  open it if the governor were.

  ## `:halt-order` is auto-eligible at every phase above 0

  The safe direction. A halt that turns out to have been unnecessary
  costs a restart; a halt delayed for approval costs whatever the
  factory builds in the meantime."
  (:require [tsukuru.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:create-production-order` is a member of
;; `write-ops` but is NEVER a member of any phase's `:auto` set below.
;; Do not add it there.
(def phases
  {0 {:label "read-only"          :writes #{}                    :auto #{}}
   1 {:label "assisted-ordering"  :writes #{:create-production-order :halt-order}
      :auto #{:halt-order}}
   2 {:label "assisted-tracking"  :writes #{:create-production-order :record-progress
                                            :record-qc :halt-order}
      :auto #{:halt-order}}
   3 {:label "supervised-auto"    :writes write-ops
      :auto #{:record-progress :record-qc :halt-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  `{:disposition kw :reason kw|nil}`."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a TsukuruGovernor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
