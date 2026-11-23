;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-creation-fixtures
  "Construct shell replies in the shape of kubectl create -o json."
  (:require [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} identity-metadata
  "Mirror the UID and resourceVersion strings emitted by the API server."
  {:uid "observed-job-uid"
   :resourceVersion "observed-creation-version"})

(defn ^{:stratum 0} requested [args]
  (json/parse-string-strict (last args) true))

(defn ^{:stratum 0} provider-reply [exit output]
  {:exit exit
   :out output})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} response [altered & args]
  (let [job (update (requested args) :metadata merge identity-metadata)]
    (provider-reply 0 (json/generate-string (altered job)))))

(comment (provider-reply 1 ""))
