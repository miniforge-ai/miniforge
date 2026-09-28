;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.current-schema
  "Contracts for the trusted runtime commit boundary."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} StoreDirectory
  [:and [:or :string [:fn #(instance? File %)]]
   [:fn #(not (str/blank? (str %)))]])

(def ^{:stratum 0} ClockReading
  [:fn #(instance? Instant %)])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} CommitArguments
  [:tuple StoreDirectory :uuid fn? fn? fn?])
