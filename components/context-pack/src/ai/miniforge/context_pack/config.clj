;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.config
  "Budget configuration loaded from EDN resources.

   Layer 0 — pure config loading, no domain logic."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private config-path "config/context-pack/budgets.edn")

(defn- load-budget-config []
  (if-let [res (io/resource config-path)]
    (get (edn/read-string (slurp res)) :context-pack/budgets {})
    {}))

(def ^:private budget-config (delay (load-budget-config)))

(defn phase-budget
  "Get the token budget for a given phase keyword."
  [phase]
  (get-in @budget-config [:phase-budgets phase]
          (get @budget-config :default-budget 2000)))

(defn repo-map-budget
  "Get the token budget allocated for the repo map."
  []
  (get @budget-config :repo-map-budget 500))

(defn max-files
  "Get the maximum number of files to include."
  []
  (get @budget-config :max-files 25))

(defn max-lines-per-file
  "Get the maximum lines per file."
  []
  (get @budget-config :max-lines-per-file 500))

(defn max-search-results
  "Get the maximum number of search results."
  []
  (get @budget-config :max-search-results 5))

(defn exhaustion-policy
  "Get the budget exhaustion policy (:fail-closed or :warn)."
  []
  (get @budget-config :exhaustion-policy :fail-closed))
