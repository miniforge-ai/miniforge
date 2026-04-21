;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.phase-software-factory.phase-config
  "Load phase defaults from EDN configuration.

   Layer 0 — pure config loading, no domain logic."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private config-path "config/phase/defaults.edn")

(defn- load-phase-defaults []
  (if-let [res (io/resource config-path)]
    (get (edn/read-string (slurp res)) :phase/defaults {})
    {}))

(def ^:private phase-defaults (delay (load-phase-defaults)))

(defn defaults-for
  "Get the default config map for a given phase keyword.
   Returns the config from defaults.edn, or an empty map if not found."
  [phase-kw]
  (get @phase-defaults phase-kw {}))
