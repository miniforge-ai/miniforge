;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-protection-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} immutable-identity-binds-protected-content-and-compliance-test
  (let [ctx {:execution/id (random-uuid)
             :execution/input {:opsv/evidence-bundle-id (random-uuid)}}
        material [:verification-environment :opsv-verification-report :report]
        password (model/record ctx {:report {:password "fake-test-password"}} material)
        ssn (model/record ctx {:report {:password "123-45-6789"}} material)
        metadata (:artifact/metadata password)
        protected (select-keys metadata [:compliance/sensitive-findings :compliance/sensitive-data
                                         :compliance/pii-handling :evidence/content-protection])]
    (is (= (:artifact/content password) (:artifact/content ssn)))
    (is (not= (:artifact/id password) (:artifact/id ssn)))
    (is (= (:content/hash metadata)
           (artifact/content-digest [(:execution/id ctx)
                                     (get-in ctx [:execution/input :opsv/evidence-bundle-id])
                                     :verification-environment
                                     {:artifact/content (:artifact/content password)
                                      :artifact/metadata protected
                                      :artifact/type (:artifact/type password)
                                      :artifact/version (:artifact/version password)}])))))

(deftest ^{:stratum 0} failure-material-remains-data-not-pipeline-control-test
  (let [failure (anomaly/anomaly :conflict "injected failure" {:password "fake-test-password"})
        record (model/record {:execution/id (random-uuid)} {:report failure}
                             [:actuation-failure :review :report])]
    (is (uuid? (:artifact/id record)))
    (is (= (redaction/redact failure) (:artifact/content record)))
    (is (redaction/clean? record))))

(deftest ^{:stratum 0} retained-protection-and-clean-legacy-identities-are-verified-test
  (let [ctx {:execution/id (random-uuid)}
        record (model/record ctx {:report {:password "fake-test-password"}} [:verification :review :report])
        legacy (protected/record ctx :verification :review {:status :passed} nil)]
    (is (protected/valid? ctx record))
    (is (protected/valid? ctx legacy))
    (doseq [changed [(assoc-in record [:artifact/metadata :compliance/sensitive-findings] [])
                     (assoc-in record [:artifact/metadata :workflow/id] (random-uuid))
                     (assoc record :artifact/type :telemetry)
                     (protected/record ctx :verification :review {:password "fake-test-password"} nil)]]
      (is (false? (protected/valid? ctx changed))))))
