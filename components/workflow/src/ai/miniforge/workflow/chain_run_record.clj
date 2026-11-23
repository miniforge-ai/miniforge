;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-run-record
  "Canonical admission records, independent of storage and execution."
  (:require [ai.miniforge.workflow.chain-composition :as composition]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} identity-matches? [record]
  (let [identity-keys [:chain/definition-id :chain/definition-version]]
    (= (select-keys record identity-keys)
       (select-keys (:chain/definition-snapshot record) identity-keys))))

(defn ^{:stratum 0} detach [record]
  (if (map? record)
    (let [value (into {} record)]
      (update value :chain/definition-snapshot composition/detach))
    record))

(comment
  ::retained-admission)
