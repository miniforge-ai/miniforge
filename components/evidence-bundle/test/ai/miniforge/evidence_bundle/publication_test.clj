;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.evidence-bundle.opsv-test-fixtures :as f]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sealed-bundle []
  (let [at #inst "2026-09-30T00:00:00Z"
        bundle (assoc f/base-bundle :evidence-bundle/id f/canonical-bundle-id
                                    :evidence/sealed-at at :compliance/created-at at)]
    (assoc bundle :evidence/content-hash (evidence/content-hash bundle))))

(defn- ^{:stratum 0} export! [bundle file]
  (let [manager (evidence/create-evidence-manager {:artifact-store :unused-test-store})
        id (:evidence-bundle/id bundle)]
    (swap! (:bundles manager) assoc id bundle)
    (evidence/export-bundle manager id (str file))))

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

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.publication-test))
