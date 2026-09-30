;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.degradation-config
  "Shared default policy for degradation recommendations and managers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} defaults
  (:degradation-policy (-> (io/resource "config/reliability/defaults.edn") slurp edn/read-string)))

(defn ^{:stratum 0} signal [mode event message & [opts]]
  (merge {:mode mode :event event :message message} opts))
