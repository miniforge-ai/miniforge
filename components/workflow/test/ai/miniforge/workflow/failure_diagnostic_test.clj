;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.failure-diagnostic-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.coerce.interface :as coerce]
            [ai.miniforge.workflow.failure-diagnostic :as diagnostic]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} checkpoint-decoding-preserves-valid-causes-and-rejects-invalid-data
  (let [cause (anomaly/sub-anomaly :timeout :anomalies.test/stalled "Socket stalled" {})
        saved (coerce/stringify-instants cause)]
    (is (= cause (diagnostic/as-anomaly cause) (diagnostic/as-anomaly saved)))
    (is (nil? (diagnostic/as-anomaly (assoc saved :anomaly/at "invalid"))))
    (is (nil? (diagnostic/as-anomaly (assoc saved :anomaly/type :invalid))))
    (is (nil? (diagnostic/as-anomaly (dissoc saved :anomaly/message))))))
