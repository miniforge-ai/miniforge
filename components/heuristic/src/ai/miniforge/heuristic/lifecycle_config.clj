;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.heuristic.lifecycle-config
  "Configuration-as-data for the heuristic lifecycle."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private defaults
  (delay
    (if-let [resource (io/resource "config/heuristic/lifecycle.edn")]
      (:heuristic/lifecycle (edn/read-string (slurp resource)))
      {})))

(defn statuses
  []
  (set (get @defaults :statuses #{})))

(defn machine-config
  []
  (get @defaults :machine-config {}))

(defn transition-events
  []
  (get @defaults :transition-events {}))
