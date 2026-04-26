;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.connector-retry.circuit-breaker-config
  "Configuration-as-data for the circuit breaker lifecycle."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private defaults
  (delay
    (if-let [resource (io/resource "config/connector-retry/circuit-breaker.edn")]
      (:connector-retry/circuit-breaker (edn/read-string (slurp resource)))
      {})))

(defn default-config
  []
  (get @defaults :default-config {}))

(defn machine-config
  []
  (get @defaults :machine-config {}))
