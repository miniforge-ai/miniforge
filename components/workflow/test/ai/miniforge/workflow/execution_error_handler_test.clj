;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-error-handler-test
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-error-handler :as handler]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} summary [overrides]
  (merge {:type :deploy-error :phase :deploy :message "Socket stalled" :data nil} overrides))

(defn- ^{:stratum 0} append-record [record ctx _exception]
  (update ctx :execution/errors conj record))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} exercise [candidate]
  (let [exception (java.net.SocketTimeoutException. "Socket stalled")
        cause (response/from-exception exception)
        original (summary {:type :phase-error :anomaly cause})
        historical (summary {})
        ctx {:execution/errors [historical original]}
        handled (handler/invoke (partial append-record candidate) ctx exception original)]
    {:before (:execution/errors ctx) :after (:execution/errors handled) :cause cause}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} only-new-matching-handler-records-receive-provenance
  (let [{:keys [before after cause]} (exercise (summary {}))]
    (is (= before (subvec after 0 (count before))))
    (is (= (summary {:anomaly cause}) (last after)))))

(deftest ^{:stratum 2} unrelated-handler-diagnostics-and-explicit-causes-are-preserved
  (let [explicit (response/make-anomaly :anomalies/forbidden "Distinct handler failure")]
    (doseq [override [{:phase :validate} {:message "Different error"}
                     {:data {:code :different}} {:anomaly explicit}]]
      (let [candidate (summary override)
            {:keys [before after]} (exercise candidate)]
        (is (= before (subvec after 0 (count before))))
        (is (= candidate (last after)))))))
