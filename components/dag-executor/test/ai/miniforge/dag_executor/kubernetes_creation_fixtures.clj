;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-creation-fixtures
  "Construct native replies from the exact UTF-8 stdin bytes sent by creation."
  (:require [cheshire.core :as json])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} identity-metadata
  "Mirror the UID and resourceVersion strings emitted by the API server."
  {:uid "observed-job-uid"
   :resourceVersion "observed-creation-version"})

(defn ^{:stratum 0} requested [args]
  (json/parse-string-strict (String. ^bytes (last args) StandardCharsets/UTF_8) true))

(defn ^{:stratum 0} provider-reply [exit output]
  {:exit exit
   :out output
   :err ""})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} provider-error [exit output diagnostic]
  (assoc (provider-reply exit output) :err diagnostic))

(defn ^{:stratum 1} created-job [job]
  (update job :metadata merge identity-metadata))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} response [altered & args]
  (let [job (created-job (requested args))]
    (provider-reply 0 (json/generate-string (altered job)))))

(comment (provider-reply 1 ""))
