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

(def FailureSource
  "Malli schema for a failure source keyword."
  taxonomy/FailureSource)

(def DependencyVendor
  "Malli schema for a dependency vendor keyword."
  taxonomy/DependencyVendor)

(def DependencyClass
  "Malli schema for a dependency class keyword."
  taxonomy/DependencyClass)

(def DependencyRetryability
  "Malli schema for a dependency retryability keyword."
  taxonomy/DependencyRetryability)

(def DependencyAttribution
  "Malli schema for canonical dependency attribution."
  taxonomy/DependencyAttribution)

(def ClassifiedFailure
  "Malli schema: [:map [:failure/class FailureClass] [:failure/message :string] ...]"
  taxonomy/ClassifiedFailure)

(def ClassifiedDependencyFailure
  "Malli schema for a classified failure with dependency attribution."
  taxonomy/ClassifiedDependencyFailure)

(def valid-failure-class?
  "Returns true if the keyword is a valid canonical failure class."
  taxonomy/valid-failure-class?)

(def valid-failure-source?
  "Returns true if the keyword is a valid canonical failure source."
  taxonomy/valid-failure-source?)

(def valid-dependency-vendor?
  "Returns true if the keyword is a known dependency vendor."
  taxonomy/valid-dependency-vendor?)

(def valid-dependency-class?
  "Returns true if the keyword is a valid canonical dependency class."
  taxonomy/valid-dependency-class?)

(def valid-dependency-retryability?
  "Returns true if the keyword is a valid dependency retryability state."
  taxonomy/valid-dependency-retryability?)

(def unknown-class?
  "Returns true if the failure class is :failure.class/unknown.
   Unknown failures MUST be treated as SLI incidents (N1 §5.3.3)."
  taxonomy/unknown-class?)

(def dependency-failure?
  "Returns true if the failure is dependency- or environment-driven."
  taxonomy/dependency-failure?)

(def retryable-dependency-failure?
  "Returns true if the dependency failure is safe to retry automatically."
  taxonomy/retryable-dependency-failure?)

(def operator-action-required?
  "Returns true if the dependency failure requires operator intervention."
  taxonomy/operator-action-required?)

(def make-dependency-attribution
  "Construct canonical dependency attribution."
  taxonomy/make-dependency-attribution)

(def make-classified-dependency-failure
  "Construct canonical classified dependency failure."
  taxonomy/make-classified-dependency-failure)

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

  (make-dependency-attribution
   {:failure/source :external-provider
    :failure/vendor :anthropic
    :dependency/class :rate-limit
    :dependency/retryability :retryable})
  ;; => {:failure/source :external-provider
  ;;     :failure/vendor :anthropic
  ;;     :dependency/class :rate-limit
  ;;     :dependency/retryability :retryable}

  :leave-this-here)
