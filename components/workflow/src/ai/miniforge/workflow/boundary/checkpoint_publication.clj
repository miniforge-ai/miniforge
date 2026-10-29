;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-publication
  "Force bytes before rename and confirm publication before acknowledgment."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private atomic-replacement
  (into-array java.nio.file.CopyOption
              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))

(defn- ^{:stratum 0} require-durable! [result]
  ;; This effect boundary adapts anomaly ports to the store's throwing write port.
  (when (anomaly/any-anomaly? result) (throw+ result))
  result)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish! [temporary target data]
  (let [bytes (.getBytes (pr-str data) StandardCharsets/UTF_8)]
    (require-durable! (durability/write-temporary-bytes! (fs/file temporary) bytes))
    (Files/move (fs/path temporary) (fs/path target) atomic-replacement)
    (require-durable! (durability/confirm! (fs/file target)))))

(comment ::durable-checkpoint-publication)
