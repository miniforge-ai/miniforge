;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-types
  "Lossless Transit extensions for durable evidence values."
  (:require [cognitect.transit :as transit])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} instant-tag "miniforge/instant")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} read-options
  {:handlers {instant-tag (transit/read-handler #(Instant/parse %))
              "list" (transit/read-handler #(apply list %))}})

(def ^{:stratum 1} write-options
  {:handlers {Instant (transit/write-handler (constantly instant-tag) str)}})
