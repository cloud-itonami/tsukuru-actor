(require '[clojure.test :as test])

(def test-namespaces
  '[tsukuru.kotoba.test-agent
    tsukuru.murakumo-test
    tsukuru.repository-contract-test])

(doseq [namespace test-namespaces]
  (require namespace))

(let [result (apply test/run-tests test-namespaces)]
  (println "==> tsukuru:" (select-keys result [:test :pass :fail :error]))
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
