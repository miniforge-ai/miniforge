;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-receipts-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.terminal-model :as terminal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} provider-failure []
  (anomaly/anomaly :fault "Provider failed" {:opsv/phase-output {:provider/outcome :unknown}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} partial-or-empty-terminal-receipts-do-not-replace-the-provider-error
  (doseq [ids [[] [(random-uuid)]]]
    (let [original (provider-failure)
          published (anomaly/anomaly :unavailable "Publication unavailable"
                                     {:opsv/phase-output {:opsv/phase-produced-artifact-refs ids}})
          attached (terminal/attach-evidence original published)]
      (is (= (dissoc original :anomaly/data) (dissoc attached :anomaly/data)))
      (is (= published (get-in attached [:anomaly/data :opsv/evidence-failure])))
      (is (= (assoc (terminal/retained-output original) :opsv/phase-produced-artifact-refs ids)
             (terminal/retained-output attached))))))

(deftest ^{:stratum 1} missing-publication-receipts-are-not-fabricated
  (let [original (provider-failure)
        published (anomaly/anomaly :unavailable "Audit unavailable" {})
        attached (terminal/attach-evidence original published)]
    (is (= (terminal/retained-output original) (terminal/retained-output attached)))))
