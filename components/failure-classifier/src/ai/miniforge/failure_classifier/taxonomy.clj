;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.failure-classifier.taxonomy
  "Canonical failure taxonomy per N1 §5.3.3.

   The single source of truth is config/failure-classifier/rules.edn.
   This namespace loads the canonical set and derives schemas and predicates.

   Layer 0: Config loading
   Layer 1: Schemas and predicates"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading

(def ^:private config
  (-> (io/resource "config/failure-classifier/rules.edn") slurp edn/read-string))

(def failure-classes
  "The complete set of canonical failure classes.
   Loaded from config/failure-classifier/rules.edn — single source of truth."
  (:failure-classes config))

(def failure-sources
  "The complete set of canonical failure sources."
  (:failure-sources config))

(def dependency-vendors
  "The supported set of known dependency vendors/platforms."
  (:dependency-vendors config))

(def dependency-classes
  "The complete set of canonical dependency classes."
  (:dependency-classes config))

(def dependency-retryabilities
  "The complete set of canonical dependency retryability states."
  (:dependency-retryabilities config))

;------------------------------------------------------------------------------ Layer 1
;; Schemas and predicates

(def FailureClass
  "Malli schema for a failure class keyword."
  (into [:enum] (sort failure-classes)))

(def FailureSource
  "Malli schema for a failure source keyword."
  (into [:enum] (sort failure-sources)))

(def DependencyVendor
  "Malli schema for a dependency vendor keyword."
  (into [:enum] (sort dependency-vendors)))

(def DependencyClass
  "Malli schema for a dependency class keyword."
  (into [:enum] (sort dependency-classes)))

(def DependencyRetryability
  "Malli schema for a dependency retryability keyword."
  (into [:enum] (sort dependency-retryabilities)))

(def DependencyAttribution
  "Malli schema for canonical dependency attribution."
  [:map
   [:failure/source FailureSource]
   [:dependency/class DependencyClass]
   [:dependency/retryability DependencyRetryability]
   [:failure/vendor {:optional true} DependencyVendor]])

(def ClassifiedFailure
  "Malli schema for a classified failure record."
  [:map
   [:failure/class FailureClass]
   [:failure/message :string]
   [:failure/context {:optional true} :map]])

(def ClassifiedDependencyFailure
  "Malli schema for a classified failure with dependency attribution."
  [:map
   [:failure/class FailureClass]
   [:failure/message :string]
   [:failure/source FailureSource]
   [:dependency/class DependencyClass]
   [:dependency/retryability DependencyRetryability]
   [:failure/vendor {:optional true} DependencyVendor]
   [:failure/context {:optional true} :map]])

(defn valid-failure-class?
  "Returns true if the keyword is a valid canonical failure class."
  [kw]
  (contains? failure-classes kw))

(defn valid-failure-source?
  "Returns true if the keyword is a valid canonical failure source."
  [kw]
  (contains? failure-sources kw))

(defn valid-dependency-vendor?
  "Returns true if the keyword is a known dependency vendor."
  [kw]
  (contains? dependency-vendors kw))

(defn valid-dependency-class?
  "Returns true if the keyword is a valid canonical dependency class."
  [kw]
  (contains? dependency-classes kw))

(defn valid-dependency-retryability?
  "Returns true if the keyword is a valid dependency retryability state."
  [kw]
  (contains? dependency-retryabilities kw))

(defn unknown-class?
  "Returns true if the failure class is :failure.class/unknown.
   Unknown failures MUST be treated as SLI incidents (N1 §5.3.3)."
  [kw]
  (= kw :failure.class/unknown))

(defn dependency-failure?
  "Returns true if the classified failure is caused by an external dependency
   or user environment condition rather than a Miniforge-internal failure."
  [{source :failure/source}]
  (contains? #{:user-env :external-provider :external-platform} source))

(defn retryable-dependency-failure?
  "Returns true if the dependency failure is safe to retry automatically."
  [{source :failure/source retryability :dependency/retryability}]
  (and (dependency-failure? {:failure/source source})
       (= retryability :retryable)))

(defn operator-action-required?
  "Returns true if the dependency failure requires human or operator action."
  [{retryability :dependency/retryability}]
  (= retryability :operator-action))

(defn- value-or-default
  "Return value when non-nil, otherwise return default-value."
  [value default-value]
  (if (nil? value)
    default-value
    value))

(defn make-dependency-attribution
  "Construct canonical dependency attribution.

   Defaults:
   - :dependency/class         => :unknown
   - :dependency/retryability  => :non-retryable"
  [{source :failure/source
    vendor :failure/vendor
    dependency-class :dependency/class
    retryability :dependency/retryability}]
  (let [resolved-class (value-or-default dependency-class :unknown)
        resolved-retryability (value-or-default retryability :non-retryable)]
    (cond-> {:failure/source source
             :dependency/class resolved-class
             :dependency/retryability resolved-retryability}
      vendor (assoc :failure/vendor vendor))))

(defn make-classified-dependency-failure
  "Construct canonical classified dependency failure."
  [{failure-class :failure/class
    message :failure/message
    context :failure/context
    :as dependency-attribution}]
  (let [attribution (make-dependency-attribution dependency-attribution)]
    (cond-> {:failure/class failure-class
             :failure/message message
             :failure/source (:failure/source attribution)
             :dependency/class (:dependency/class attribution)
             :dependency/retryability (:dependency/retryability attribution)}
      (:failure/vendor attribution) (assoc :failure/vendor (:failure/vendor attribution))
      context (assoc :failure/context context))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  failure-classes
  ;; => #{:failure.class/agent-error :failure.class/timeout ...}

  (valid-failure-class? :failure.class/timeout)    ;; => true
  (valid-failure-class? :failure.class/bogus)      ;; => false
  (unknown-class? :failure.class/unknown)          ;; => true
  (valid-failure-source? :external-provider)       ;; => true
  (valid-dependency-class? :rate-limit)            ;; => true
  (retryable-dependency-failure?
   {:failure/source :external-provider
    :dependency/retryability :retryable})          ;; => true

  :leave-this-here)
