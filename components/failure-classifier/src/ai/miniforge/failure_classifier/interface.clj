;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.failure-classifier.interface
  "Public API for failure classification per N1 §5.3.3.

   Classifies failures into a canonical taxonomy enabling structured
   failure analysis, SLI computation (N1 §5.5), and targeted remediation.

   Classification is performed by the runtime, NOT by agents.

   Layer 0: Taxonomy (schemas, predicates)
   Layer 1: Classification (pure functions)"
  (:require
   [ai.miniforge.failure-classifier.taxonomy :as taxonomy]
   [ai.miniforge.failure-classifier.classifier :as classifier]))

;------------------------------------------------------------------------------ Layer 0
;; Taxonomy

(def failure-classes
  "The complete set of canonical failure class keywords.
   Loaded from config/failure-classifier/rules.edn — see that file for descriptions."
  taxonomy/failure-classes)

(def FailureClass
  "Malli schema for a failure class keyword."
  taxonomy/FailureClass)

(def ClassifiedFailure
  "Malli schema: [:map [:failure/class FailureClass] [:failure/message :string] ...]"
  taxonomy/ClassifiedFailure)

(def valid-failure-class?
  "Returns true if the keyword is a valid canonical failure class."
  taxonomy/valid-failure-class?)

(def unknown-class?
  "Returns true if the failure class is :failure.class/unknown.
   Unknown failures MUST be treated as SLI incidents (N1 §5.3.3)."
  taxonomy/unknown-class?)

;------------------------------------------------------------------------------ Layer 1
;; Classification

(def classify
  "Classify a failure into the canonical taxonomy.

   Attempts classification by:
   1. Anomaly category (most precise)
   2. Exception class name (JVM-level signal)
   3. Error message pattern matching (broadest)
   4. Falls back to :failure.class/unknown

   Arguments:
     failure-context - map with optional keys:
       :anomaly/category   - keyword from anomaly taxonomy
       :exception/class    - string, Java exception class name
       :error/message      - string, human-readable error
       :phase              - keyword, workflow phase
       :tool/id            - keyword, tool that failed

   Returns: keyword from the canonical failure class set.

   Example:
     (classify {:error/message \"Connection refused\"})
     ;; => :failure.class/external

     (classify {:anomaly/category :anomalies/timeout})
     ;; => :failure.class/timeout"
  classifier/classify-failure)

(def classify-exception
  "Classify a Throwable directly.

   Example:
     (classify-exception (java.net.SocketTimeoutException. \"timeout\"))
     ;; => :failure.class/timeout"
  classifier/classify-exception)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify {:error/message "Connection refused to api.example.com"})
  ;; => :failure.class/external

  (classify {:anomaly/category :anomalies.gate/check-failed})
  ;; => :failure.class/policy

  (classify {:error/message "Something mysterious happened"})
  ;; => :failure.class/unknown

  :leave-this-here)
