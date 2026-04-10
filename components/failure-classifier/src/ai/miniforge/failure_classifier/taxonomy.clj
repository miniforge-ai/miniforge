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

;------------------------------------------------------------------------------ Layer 1
;; Schemas and predicates

(def FailureClass
  "Malli schema for a failure class keyword."
  (into [:enum] (sort failure-classes)))

(def ClassifiedFailure
  "Malli schema for a classified failure record."
  [:map
   [:failure/class FailureClass]
   [:failure/message :string]
   [:failure/context {:optional true} :map]])

(defn valid-failure-class?
  "Returns true if the keyword is a valid canonical failure class."
  [kw]
  (contains? failure-classes kw))

(defn unknown-class?
  "Returns true if the failure class is :failure.class/unknown.
   Unknown failures MUST be treated as SLI incidents (N1 §5.3.3)."
  [kw]
  (= kw :failure.class/unknown))

;------------------------------------------------------------------------------ Rich Comment
(comment
  failure-classes
  ;; => #{:failure.class/agent-error :failure.class/timeout ...}

  (valid-failure-class? :failure.class/timeout)    ;; => true
  (valid-failure-class? :failure.class/bogus)      ;; => false
  (unknown-class? :failure.class/unknown)          ;; => true

  :leave-this-here)
