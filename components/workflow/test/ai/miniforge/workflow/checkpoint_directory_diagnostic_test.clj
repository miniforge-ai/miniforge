;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-directory-diagnostic-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.messages :as messages]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} requested-path
  "The nested request differs from the ancestor actually refused by policy."
  "requested/nested")

(def ^{:stratum 0 :private true} unsafe-ancestor
  "Diagnostic evidence must identify this refused ancestor, not the request."
  "requested")

(defn- ^{:stratum 0} io-failure [& _]
  (anomaly/anomaly :fault "injected I/O failure" {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} policy-refusal [& _]
  (anomaly/sub-anomaly :unauthorized :anomalies.file-durability/unsafe-directory
                       "injected policy refusal" {:durability/path unsafe-ancestor}))

(deftest ^{:stratum 1} ordinary-io-failures-do-not-prescribe-permission-changes
  (with-redefs [durability/resolve-protected-directory io-failure]
    (is (thrown-with-msg? IOException
                         (re-pattern (java.util.regex.Pattern/quote
                                      (messages/system :checkpoint/directory-io-failed {:path requested-path})))
                         (directory/existing! requested-path)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} policy-refusals-identify-the-offending-ancestor
  (with-redefs [durability/prepare-protected-directory! policy-refusal]
    (is (thrown-with-msg? IOException
                         (re-pattern (java.util.regex.Pattern/quote
                                      (messages/system :checkpoint/unsafe-directory {:path unsafe-ancestor})))
                         (directory/prepare! requested-path)))))
