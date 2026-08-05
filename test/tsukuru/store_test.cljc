(ns tsukuru.store-test
  "The store's own invariants: what it appends, what it overwrites, and
  what it refuses to hold at all (ADR-2800003200 Phase 2)."
  (:require [clojure.test :refer [deftest is testing]]
            [tsukuru.store :as store]))

(def did (:factory/did store/demo-factory))

(deftest progress-is-appended-never-replaced
  (let [st (store/mem-store)]
    (store/commit-record! st {:op :record-progress :value {"id" "po-1" "stage" "dispatched"}})
    (store/commit-record! st {:op :record-progress :value {"id" "po-1" "stage" "in-production"}})
    (is (= ["dispatched" "in-production"] (mapv #(get % "stage") (store/progress-for st "po-1")))
        "overwriting would hide a stage the factory actually reported")))

(deftest qc-records-the-verdict-and-moves-the-order
  (let [st (store/mem-store)]
    (store/commit-record! st {:op :record-qc
                              :value {"id" "po-1" "result" "fail"
                                      "order" {"id" "po-1" "state" "quarantine"}}})
    (is (= "fail" (get (store/qc-for st "po-1") "result")))
    (is (= "quarantine" (get (store/production-order-record st "po-1") "state")))))

(deftest the-ledger-is-append-only
  (let [st (store/mem-store)]
    (store/append-ledger! st {:t :committed :ref "po-1"})
    (store/append-ledger! st {:t :approval-requested :ref "po-2"})
    (is (= 2 (count (store/ledger st))))
    (is (= [:committed :approval-requested] (mapv :t (store/ledger st))))))

(deftest consented-did-is-the-boundary-between-the-two-planes
  (testing "did:web ids are the trading plane"
    (is (store/consented-did? did))
    (is (store/consented-did? "did:web:anything")))
  (testing "the research plane's own id shape is refused"
    (is (not (store/consented-did? "candidate:manufacturer-directory/jp/fanuc-corporation")))
    (is (not (store/consented-did? "factory/registry-seed/tokyo-electron"))
        "manufacturer-registry-seed.edn's shape is also research, not a relationship")
    (is (not (store/consented-did? nil)))
    (is (not (store/consented-did? "")))))

(deftest reads-that-belong-to-other-actors-are-reads-only
  (let [st (store/mem-store)]
    (is (some? (store/seller-credential st did))
        "written by -marketplace-onboarding, read here by join")
    (is (some? (store/order-record st "ord-1"))
        "written by -marketplace-order, read here by join")
    (testing "and the Store protocol offers no way to write either"
      (is (not (contains? (set (map :name (vals (:sigs store/Store)))) 'put-credential!)))
      (is (not (contains? (set (map :name (vals (:sigs store/Store)))) 'put-order!))))))

(deftest memory-backend-declares-itself-non-durable
  (is (false? (store/durable? (store/mem-store)))
      "so nothing mistakes a test fixture for a store that survives a restart"))
