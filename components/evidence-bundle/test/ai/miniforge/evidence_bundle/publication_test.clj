;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.evidence-bundle.opsv-test-fixtures :as f]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sealed-bundle []
  (let [at #inst "2026-09-30T00:00:00Z"
        bundle (assoc f/base-bundle :evidence-bundle/id f/canonical-bundle-id
                                    :compliance/sensitive-data false :compliance/pii-handling :none
                                    :evidence/sealed-at at :compliance/created-at at)]
    (assoc bundle :evidence/content-hash (evidence/content-hash bundle))))

(defn- ^{:stratum 0} export! [bundle file]
  (let [manager (evidence/create-evidence-manager {:artifact-store :unused-test-store})
        id (:evidence-bundle/id bundle)]
    (swap! (:bundles manager) assoc id bundle)
    (evidence/export-bundle manager id (str file))))

(defn- ^{:stratum 0} legacy-default-writer [writer destination & options]
  (apply writer destination (concat [:encoding "US-ASCII"] options)))

(defn- ^{:stratum 0} rehash-with [bundle overrides]
  (let [changed (merge (dissoc bundle :evidence/content-hash) overrides)]
    (assoc changed :evidence/content-hash (evidence/content-hash changed))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} manager-export-checks-seal-before-writing
  (let [sealed (sealed-bundle)
        file (java.io.File/createTempFile "evidence-publication-" ".edn")]
    (try
      (is (:valid? (evidence/validate-published-bundle sealed)))
      (is (true? (export! sealed file)))
      (is (= sealed (edn/read-string (slurp file))))
      (doseq [invalid [(dissoc sealed :evidence/content-hash :evidence/sealed-at)
                       (assoc-in sealed [:evidence/outcome :outcome/success] false)
                       (assoc sealed :evidence/content-hash "wrong")]]
        (spit file "unchanged")
        (is (false? (:valid? (evidence/validate-published-bundle invalid))))
        (is (false? (export! invalid file)))
        (is (= "unchanged" (slurp file))))
      (finally (.delete file)))))

(deftest ^{:stratum 1} manager-export-uses-utf8-independently-of-platform-default
  (let [base (assoc (dissoc (sealed-bundle) :evidence/content-hash) :test/text "café 東京")
        sealed (assoc base :evidence/content-hash (evidence/content-hash base))
        file (java.io.File/createTempFile "evidence-utf8-" ".edn")
        writer (partial legacy-default-writer io/writer)]
    (try
      (with-redefs [io/writer writer]
        (is (true? (export! sealed file))))
      (let [decoded (evidence/read-bundle-edn file)]
        (is (= (:test/text sealed) (:test/text decoded)))
        (is (:valid? (evidence/validate-published-bundle decoded))))
      (finally (.delete file)))))

(deftest ^{:stratum 1} exported-instant-precision-preserves-the-seal
  (let [at (java.time.Instant/parse "2026-09-30T00:00:00.123456789Z")
        base (assoc (dissoc (sealed-bundle) :evidence/content-hash) :evidence/sealed-at at)
        sealed (assoc base :evidence/content-hash (evidence/content-hash base))
        decoded (evidence/decode-bundle-edn (evidence/encode-bundle-edn sealed))]
    (is (= at (:evidence/sealed-at decoded)))
    (is (:valid? (evidence/validate-published-bundle decoded)))
    (is (= (:evidence/content-hash sealed) (:evidence/content-hash decoded)))
    (is (nil? (evidence/decode-bundle-edn "#object [unsupported]")))))

(deftest ^{:stratum 1} valid-digests-do-not-authorize-unsafe-publication
  (let [sealed (sealed-bundle)
        file (java.io.File/createTempFile "evidence-compliance-" ".edn")]
    (try
      (doseq [overrides [{:test/text "AKIAIOSFODNN7EXAMPLE"}
                        {:test/text "000-00-0000"}
                        {:compliance/sensitive-data true :evidence/contains-pii? true
                         :compliance/sensitive-findings [{:finding/type :payment-card}]}]]
        (let [bundle (rehash-with sealed overrides)
              report (evidence/validate-published-bundle bundle)]
          (is (:valid? (evidence/validate-canonical-bundle bundle)))
          (is (false? (:valid? report)))
          (is (= [:invalid-publication-compliance] (mapv :code (:errors report))))
          (spit file "unchanged")
          (is (false? (export! bundle file)))
          (is (= "unchanged" (slurp file)))))
      (finally (.delete file)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.publication-test))
