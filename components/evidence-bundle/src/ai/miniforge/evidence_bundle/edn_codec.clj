;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.edn-codec
  "N6 canonical EDN with nanosecond-preserving instant reads."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read-instant [value]
  (java.time.Instant/parse value))

(defn ^{:stratum 0} encode [bundle]
  (hash/canonical-edn bundle))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode-with-exception-handling [text]
  ;; No slingshot dependency; this is the untrusted EDN parsing boundary.
  (try
    (when (and (string? text) (<= (count text) 16777216))
      (edn/read-string {:readers {'inst read-instant}} text))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _ nil)))

(comment
  (decode-with-exception-handling "{}"))
