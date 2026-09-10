(ns tsukuru.repository-contract-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]))

(def repository-root (.getCanonicalFile (io/file ".")))

(def ignored-dirs
  "Directories these contracts say nothing about.

  `node_modules` and `dist` arrive with the Worker build (ADR-2800003200
  Phase 2) and are not committed; scanning them would make the suite pass
  or fail depending on whether someone had run a build, which is worse
  than not scanning them at all."
  #{".git" "node_modules" "dist" ".cpcache" ".shadow-cljs"})

(defn files-under [root]
  (->> (file-seq (io/file repository-root root))
       (remove (fn [f] (some #(str/includes? (.getPath f)
                                             (str java.io.File/separator % java.io.File/separator))
                             ignored-dirs)))
       (filter #(.isFile %))))

(def build-manifests
  "JSON files that are NOT external contracts.

  This contract exists so an AT-proto lexicon, a BPMN process or a JSON
  schema cannot be dropped anywhere but `wire/` — those are the
  repository's published surface. A JavaScript build manifest is not part
  of that surface: `package.json` is what `shadow-cljs` needs to compile
  the Worker, and it is meaningless to anyone outside this repo. Listing
  it explicitly rather than loosening the regex keeps the contract narrow
  — anything else ending in .json still has to live under wire/."
  #{"package.json" "package-lock.json"})

(deftest canonical-edn-is-readable
  (doseq [file (files-under ".")
          :when (str/ends-with? (.getName file) ".edn")]
    (is (some? (edn/read-string (slurp file))) (.getPath file))))

(deftest external-contracts-live-under-wire
  (doseq [file (files-under ".")
          :let [path (.getPath file)]
          :when (and (re-find #"\.(?:json|jsonld|bpmn)$" path)
                     (not (contains? build-manifests (.getName file))))]
    (is (or (str/includes? path (str java.io.File/separator "wire" java.io.File/separator))
            (str/ends-with? path (str java.io.File/separator ".well-known" java.io.File/separator "did.json")))
        path)))

(deftest deprecated-runtimes-are-absent
  (testing "Go, TinyGo, and shell sources are pruned"
    (doseq [file (files-under ".")]
      (is (not (re-find #"\.(?:go|sh)$" (.getName file))) (.getPath file)))))
