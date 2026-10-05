;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.representation-test
  (:require [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} OpaqueRecord [value])

(deftest ^{:stratum 0} supported-values-include-keys-and-metadata
  (doseq [value [nil true false "text" \a :field 'field 42 2.5 1/2
                (random-uuid) #inst "2026-10-05" (java.time.Instant/now)
                [] {} #{} '() '(1 2) {:nested [nil #{'field}]}]]
    (is (redaction/supported? value))
    (is (redaction/supported? (redaction/redact value)))
    (is (redaction/supported? (with-meta {value :value} {:nested value})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-representations-remain-opaque
  (let [realized? (atom false)
        deferred (delay (reset! realized? true))]
    (doseq [value [(->OpaqueRecord :value) deferred (atom :value)
                  (object-array [:value]) (java.util.ArrayList. [:value])
                  (java.util.HashMap. {:key :value}) (map identity [:value])]
            nested [value [value] {value :key} (with-meta [] {:nested value})
                    (with-meta 'field {:nested value})]]
      (is (false? (redaction/supported? nested))))
    (is (false? @realized?))))

(comment
  (redaction/supported? {:nested [1 2]}))
