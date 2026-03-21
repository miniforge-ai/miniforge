;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0.

(ns ai.miniforge.agent-runtime.failure-taxonomy-test
  "Comprehensive tests for the canonical 10-class failure taxonomy.

   Covers:
     - Taxonomy data integrity (structure, completeness, ordering)
     - Derived lookup table consistency
     - valid-class? predicate (membership, type safety)
     - safe-class coercion (never-nil contract)
     - sli-incident? (conservative-default contract)
     - retry-hint (safe fallback contract)"
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.set :as set]
            [ai.miniforge.agent-runtime.failure-taxonomy :as sut]))

;;------------------------------------------------------------------------------
;; Test fixtures and constants

(def ^:private all-expected-classes
  "The 10 canonical class keywords that must always be present."
  #{:failure.class/agent-backend
    :failure.class/task-code
    :failure.class/external
    :failure.class/timeout
    :failure.class/resource-exhaustion
    :failure.class/network
    :failure.class/auth
    :failure.class/invalid-input
    :failure.class/internal
    :failure.class/unknown})

(def ^:private sli-impacting-classes
  "Classes that count against the error-budget SLO."
  #{:failure.class/agent-backend
    :failure.class/external
    :failure.class/timeout
    :failure.class/resource-exhaustion
    :failure.class/network
    :failure.class/auth
    :failure.class/internal
    :failure.class/unknown})

(def ^:private non-sli-classes
  "Classes excluded from the error budget (operator/SDLC noise)."
  #{:failure.class/task-code
    :failure.class/invalid-input})

(def ^:private known-retry-hints
  #{:repair-and-retry
    :retry-with-backoff
    :retry-with-fresh-context
    :retry-with-extended-timeout
    :wait-for-budget-reset
    :fix-credentials-then-retry
    :fix-input-then-retry
    :escalate-to-human})

(def ^:private required-heuristic-keys
  #{:exception-types :error-message-patterns :status-codes :error-codes})

(def ^:private required-entry-keys
  #{:keyword :domain :heuristics :retry-hint :sli-impact})

;;==============================================================================
;; SECTION 1 — Taxonomy vector integrity
;;==============================================================================

(deftest taxonomy-exists-and-is-vector
  (testing "taxonomy is a non-nil Clojure vector"
    (is (vector? sut/taxonomy))
    (is (some? sut/taxonomy))))

(deftest taxonomy-has-exactly-10-entries
  (testing "canonical taxonomy must have exactly 10 entries (N1 §5.3.3)"
    (is (= 10 (count sut/taxonomy)))))

(deftest taxonomy-first-entry-is-agent-backend
  (testing ":failure.class/agent-backend must be first (legacy compat, most common)"
    (is (= :failure.class/agent-backend
           (:keyword (first sut/taxonomy))))))

(deftest taxonomy-last-entry-is-unknown
  (testing ":failure.class/unknown must be last (mandatory catch-all sentinel)"
    (is (= :failure.class/unknown
           (:keyword (last sut/taxonomy))))))

(deftest taxonomy-contains-all-10-canonical-classes
  (testing "all 10 expected class keywords are present"
    (let [actual-keywords (set (map :keyword sut/taxonomy))]
      (is (= all-expected-classes actual-keywords)))))

(deftest taxonomy-has-no-duplicate-keywords
  (testing "each class keyword appears exactly once"
    (let [keywords (map :keyword sut/taxonomy)]
      (is (= (count keywords)
             (count (distinct keywords)))))))

(deftest taxonomy-all-keywords-in-failure-class-namespace
  (testing "every entry keyword is qualified under :failure.class/*"
    (doseq [entry sut/taxonomy]
      (is (= "failure.class" (namespace (:keyword entry)))
          (str "Expected :failure.class/* namespace, got: " (:keyword entry))))))

(deftest taxonomy-all-entries-have-required-keys
  (testing "every entry has all required structural keys"
    (doseq [entry sut/taxonomy]
      (is (set/subset? required-entry-keys (set (keys entry)))
          (str "Entry " (:keyword entry) " is missing required keys")))))

(deftest taxonomy-all-entries-have-non-empty-domain
  (testing "every entry has a meaningful :domain string (> 20 chars per spec)"
    (doseq [entry sut/taxonomy]
      (is (string? (:domain entry))
          (str (:keyword entry) " :domain must be a string"))
      (is (> (count (:domain entry)) 20)
          (str (:keyword entry) " :domain is too short: " (:domain entry))))))

(deftest taxonomy-all-entries-have-valid-sli-impact
  (testing "every :sli-impact value is a boolean (not nil, not truthy)"
    (doseq [entry sut/taxonomy]
      (is (boolean? (:sli-impact entry))
          (str (:keyword entry) " :sli-impact must be a boolean")))))

(deftest taxonomy-all-entries-have-known-retry-hint
  (testing "every :retry-hint is one of the 8 canonical hint keywords"
    (doseq [entry sut/taxonomy]
      (is (contains? known-retry-hints (:retry-hint entry))
          (str (:keyword entry) " has unknown retry-hint: " (:retry-hint entry))))))

(deftest taxonomy-all-entries-have-complete-heuristics
  (testing "every :heuristics map has all 4 required sub-keys"
    (doseq [entry sut/taxonomy]
      (let [h (:heuristics entry)]
        (is (map? h)
            (str (:keyword entry) " :heuristics must be a map"))
        (is (set/subset? required-heuristic-keys (set (keys h)))
            (str (:keyword entry) " :heuristics missing keys"))))))

(deftest taxonomy-heuristic-vectors-are-seqs
  (testing "all heuristic sub-values are sequential (vector or list)"
    (doseq [entry sut/taxonomy]
      (let [h (:heuristics entry)
            kw (:keyword entry)]
        (is (sequential? (:exception-types h))
            (str kw " :exception-types must be sequential"))
        (is (sequential? (:error-message-patterns h))
            (str kw " :error-message-patterns must be sequential"))
        (is (sequential? (:status-codes h))
            (str kw " :status-codes must be sequential"))
        (is (sequential? (:error-codes h))
            (str kw " :error-codes must be sequential"))))))

(deftest taxonomy-sli-impact-per-class
  (testing "individual SLI-impact assignments match spec"
    ;; Non-SLI classes: only task-code and invalid-input
    (is (false? (:sli-impact (get sut/class->entry :failure.class/task-code))))
    (is (false? (:sli-impact (get sut/class->entry :failure.class/invalid-input))))
    ;; All others burn error budget
    (doseq [cls sli-impacting-classes]
      (is (true? (:sli-impact (get sut/class->entry cls)))
          (str cls " should have sli-impact=true")))))

(deftest taxonomy-unknown-sli-impact-always-true
  (testing ":failure.class/unknown must always have sli-impact=true (meta-reliability signal)"
    (is (true? (:sli-impact (get sut/class->entry :failure.class/unknown))))))

(deftest taxonomy-retry-hints-per-class
  (testing "retry hints match canonical spec"
    (are [cls hint] (= hint (:retry-hint (get sut/class->entry cls)))
      :failure.class/agent-backend       :retry-with-fresh-context
      :failure.class/task-code           :repair-and-retry
      :failure.class/external            :retry-with-backoff
      :failure.class/timeout             :retry-with-extended-timeout
      :failure.class/resource-exhaustion :wait-for-budget-reset
      :failure.class/network             :retry-with-backoff
      :failure.class/auth                :fix-credentials-then-retry
      :failure.class/invalid-input       :fix-input-then-retry
      :failure.class/internal            :escalate-to-human
      :failure.class/unknown             :escalate-to-human)))

(deftest taxonomy-error-codes-are-strings
  (testing "all :error-codes entries are uppercase strings"
    (doseq [entry sut/taxonomy
            code (get-in entry [:heuristics :error-codes])]
      (is (string? code)
          (str (:keyword entry) " error-code must be string: " code))
      (is (= code (clojure.string/upper-case code))
          (str (:keyword entry) " error-code must be UPPER_CASE: " code)))))

(deftest taxonomy-status-codes-are-integers
  (testing "all :status-codes entries are integers"
    (doseq [entry sut/taxonomy
            code (get-in entry [:heuristics :status-codes])]
      (is (integer? code)
          (str (:keyword entry) " status-code must be integer: " code)))))

(deftest taxonomy-specific-error-codes-present
  (testing "spot-check critical error codes are mapped to correct classes"
    (let [code->class
          (into {}
                (for [entry sut/taxonomy
                      code  (get-in entry [:heuristics :error-codes])]
                  [code (:keyword entry)]))

          assertions
          {"AUTH_FAILED"         :failure.class/auth
           "UNAUTHORIZED"        :failure.class/auth
           "FORBIDDEN"           :failure.class/auth
           "TOKEN_EXPIRED"       :failure.class/auth
           "RATE_LIMIT"          :failure.class/resource-exhaustion
           "QUOTA_EXCEEDED"      :failure.class/resource-exhaustion
           "OOM"                 :failure.class/resource-exhaustion
           "ENOSPC"              :failure.class/resource-exhaustion
           "TIMEOUT"             :failure.class/timeout
           "ETIMEDOUT"           :failure.class/timeout
           "DEADLINE_EXCEEDED"   :failure.class/timeout
           "ECONNREFUSED"        :failure.class/network
           "ECONNRESET"          :failure.class/network
           "ENOTFOUND"           :failure.class/network
           "EHOSTUNREACH"        :failure.class/network
           "ENETUNREACH"         :failure.class/network
           "COMPILE_ERROR"       :failure.class/task-code
           "TEST_FAILURE"        :failure.class/task-code
           "AGENT_CRASH"         :failure.class/agent-backend
           "AGENT_LOOP"          :failure.class/agent-backend
           "AGENT_HALLUCINATION" :failure.class/agent-backend
           "INTERNAL_ERROR"      :failure.class/internal
           "DEADLOCK"            :failure.class/internal
           "DAG_EXECUTOR_ERROR"  :failure.class/internal
           "INVALID_INPUT"       :failure.class/invalid-input
           "SCHEMA_VIOLATION"    :failure.class/invalid-input
           "UNKNOWN"             :failure.class/unknown}]
      (doseq [[code expected-class] assertions]
        (is (= expected-class (get code->class code))
            (str "Error code " code " should map to " expected-class))))))

(deftest taxonomy-specific-http-status-codes-present
  (testing "spot-check HTTP status codes are in correct class entries"
    (let [status->classes
          (reduce (fn [acc entry]
                    (reduce (fn [m code]
                              (update m code (fnil conj #{}) (:keyword entry)))
                            acc
                            (get-in entry [:heuristics :status-codes])))
                  {}
                  sut/taxonomy)]
      (is (contains? (get status->classes 400) :failure.class/invalid-input))
      (is (contains? (get status->classes 401) :failure.class/auth))
      (is (contains? (get status->classes 403) :failure.class/auth))
      (is (contains? (get status->classes 408) :failure.class/timeout))
      (is (contains? (get status->classes 422) :failure.class/invalid-input))
      (is (contains? (get status->classes 429) :failure.class/resource-exhaustion))
      (is (contains? (get status->classes 500) :failure.class/internal))
      (is (contains? (get status->classes 502) :failure.class/external))
      (is (contains? (get status->classes 503) :failure.class/internal))
      (is (contains? (get status->classes 504) :failure.class/timeout)))))

;;==============================================================================
;; SECTION 2 — Derived lookup tables
;;==============================================================================

(deftest all-classes-is-sorted-set
  (testing "all-classes is a sorted-set for deterministic ordering"
    (is (instance? clojure.lang.PersistentTreeSet sut/all-classes))))

(deftest all-classes-has-10-members
  (testing "all-classes has exactly 10 members"
    (is (= 10 (count sut/all-classes)))))

(deftest all-classes-contains-all-expected-keywords
  (testing "all-classes contains every expected class keyword"
    (is (= all-expected-classes (set sut/all-classes)))))

(deftest all-classes-no-extra-members
  (testing "all-classes contains no keywords beyond the canonical 10"
    (is (empty? (set/difference (set sut/all-classes) all-expected-classes)))))

(deftest class->entry-has-10-entries
  (testing "class->entry map has exactly 10 keys"
    (is (= 10 (count sut/class->entry)))))

(deftest class->entry-keys-match-all-classes
  (testing "class->entry keys equal all-classes members"
    (is (= (set sut/all-classes) (set (keys sut/class->entry))))))

(deftest class->entry-values-are-full-maps
  (testing "class->entry values contain all required entry keys"
    (doseq [[kw entry] sut/class->entry]
      (is (= kw (:keyword entry))
          (str "Entry key " kw " doesn't match :keyword field"))
      (is (set/subset? required-entry-keys (set (keys entry)))
          (str "Entry for " kw " missing required keys")))))

(deftest class->entry-consistent-with-taxonomy
  (testing "class->entry is exactly consistent with taxonomy vector"
    (doseq [entry sut/taxonomy]
      (is (= entry (get sut/class->entry (:keyword entry)))
          (str "class->entry inconsistency for " (:keyword entry))))))

(deftest class->retry-hint-has-10-entries
  (testing "class->retry-hint has exactly 10 keys"
    (is (= 10 (count sut/class->retry-hint)))))

(deftest class->retry-hint-keys-match-all-classes
  (testing "class->retry-hint keys equal all-classes members"
    (is (= (set sut/all-classes) (set (keys sut/class->retry-hint))))))

(deftest class->retry-hint-all-values-are-valid-hints
  (testing "every value in class->retry-hint is a known retry hint keyword"
    (doseq [[_ hint] sut/class->retry-hint]
      (is (contains? known-retry-hints hint)
          (str "Unknown retry hint: " hint)))))

(deftest class->sli-impact-has-10-entries
  (testing "class->sli-impact has exactly 10 keys"
    (is (= 10 (count sut/class->sli-impact)))))

(deftest class->sli-impact-keys-match-all-classes
  (testing "class->sli-impact keys equal all-classes members"
    (is (= (set sut/all-classes) (set (keys sut/class->sli-impact))))))

(deftest class->sli-impact-values-are-booleans
  (testing "every value in class->sli-impact is a boolean"
    (doseq [[_ impact] sut/class->sli-impact]
      (is (boolean? impact)
          (str "sli-impact must be boolean, got: " (type impact))))))

(deftest class->sli-impact-exactly-two-false
  (testing "exactly 2 classes have sli-impact=false"
    (let [false-classes (into #{} (keep (fn [[k v]] (when (false? v) k))
                                        sut/class->sli-impact))]
      (is (= 2 (count false-classes)))
      (is (= non-sli-classes false-classes)))))

(deftest class->sli-impact-consistent-with-taxonomy
  (testing "class->sli-impact derived values match raw taxonomy :sli-impact fields"
    (doseq [entry sut/taxonomy]
      (is (= (:sli-impact entry)
             (get sut/class->sli-impact (:keyword entry)))
          (str "Inconsistency for " (:keyword entry))))))

;;==============================================================================
;; SECTION 3 — valid-class? predicate
;;==============================================================================

(deftest valid-class-returns-true-for-all-10-canonical-classes
  (testing "valid-class? returns true for every canonical class keyword"
    (doseq [cls all-expected-classes]
      (is (true? (sut/valid-class? cls))
          (str cls " should be valid")))))

(deftest valid-class-returns-false-for-nil
  (testing "valid-class? returns false for nil input"
    (is (false? (sut/valid-class? nil)))))

(deftest valid-class-returns-false-for-unknown-keyword
  (testing "valid-class? returns false for :failure.class/bogus"
    (is (false? (sut/valid-class? :failure.class/bogus)))))

(deftest valid-class-returns-false-for-wrong-namespace
  (testing "valid-class? returns false for keyword in wrong namespace"
    (is (false? (sut/valid-class? :failure/timeout)))
    (is (false? (sut/valid-class? :timeout)))
    (is (false? (sut/valid-class? ::timeout)))))

(deftest valid-class-returns-false-for-string
  (testing "valid-class? returns false for a string representation of a class"
    (is (false? (sut/valid-class? ":failure.class/timeout")))
    (is (false? (sut/valid-class? "timeout")))))

(deftest valid-class-returns-false-for-integer
  (testing "valid-class? returns false for an integer"
    (is (false? (sut/valid-class? 0)))
    (is (false? (sut/valid-class? 42)))))

(deftest valid-class-returns-false-for-map
  (testing "valid-class? returns false for a map (e.g. full taxonomy entry)"
    (is (false? (sut/valid-class? (first sut/taxonomy))))))

(deftest valid-class-returns-boolean-not-truthy
  (testing "valid-class? always returns exactly a Boolean, never nil or truthy"
    (doseq [cls all-expected-classes]
      (is (instance? Boolean (sut/valid-class? cls))
          (str cls " should return Boolean true")))
    (is (instance? Boolean (sut/valid-class? nil)))
    (is (instance? Boolean (sut/valid-class? :bogus)))))

(deftest valid-class-is-deterministic
  (testing "valid-class? returns the same result for repeated calls (pure fn)"
    (doseq [cls all-expected-classes]
      (let [r1 (sut/valid-class? cls)
            r2 (sut/valid-class? cls)]
        (is (= r1 r2))))
    (let [r1 (sut/valid-class? nil)
          r2 (sut/valid-class? nil)]
      (is (= r1 r2)))))

;;==============================================================================
;; SECTION 4 — safe-class coercion (never-nil contract)
;;==============================================================================

(deftest safe-class-returns-input-unchanged-for-all-canonical-classes
  (testing "safe-class returns each canonical class keyword unchanged"
    (doseq [cls all-expected-classes]
      (is (= cls (sut/safe-class cls))
          (str cls " should pass through safe-class unchanged")))))

(deftest safe-class-returns-unknown-for-nil
  (testing "safe-class promotes nil to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class nil)))))

(deftest safe-class-returns-unknown-for-unknown-keyword
  (testing "safe-class promotes :failure.class/bogus to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class :failure.class/bogus)))))

(deftest safe-class-returns-unknown-for-wrong-namespace
  (testing "safe-class promotes wrong-namespace keywords to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class :failure/timeout)))
    (is (= :failure.class/unknown (sut/safe-class :timeout)))
    (is (= :failure.class/unknown (sut/safe-class ::timeout)))))

(deftest safe-class-returns-unknown-for-string
  (testing "safe-class promotes string values to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class ":failure.class/timeout")))
    (is (= :failure.class/unknown (sut/safe-class "timeout")))
    (is (= :failure.class/unknown (sut/safe-class "")))))

(deftest safe-class-returns-unknown-for-integer
  (testing "safe-class promotes integers to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class 0)))
    (is (= :failure.class/unknown (sut/safe-class 500)))
    (is (= :failure.class/unknown (sut/safe-class -1)))))

(deftest safe-class-returns-unknown-for-boolean
  (testing "safe-class promotes booleans to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class true)))
    (is (= :failure.class/unknown (sut/safe-class false)))))

(deftest safe-class-returns-unknown-for-collection
  (testing "safe-class promotes collections to :failure.class/unknown"
    (is (= :failure.class/unknown (sut/safe-class [])))
    (is (= :failure.class/unknown (sut/safe-class {})))
    (is (= :failure.class/unknown (sut/safe-class #{})))))

(deftest safe-class-never-returns-nil
  (testing "safe-class never returns nil — never-nil contract"
    (let [inputs [nil :failure.class/bogus :bogus "" 0 [] {} false
                  :failure.class/unknown  ; valid — returns itself
                  :failure.class/timeout  ; valid — returns itself
                  ]]
      (doseq [input inputs]
        (is (some? (sut/safe-class input))
            (str "safe-class must not return nil for input: " input))))))

(deftest safe-class-output-is-always-valid-class
  (testing "safe-class output always passes valid-class? check"
    (let [inputs [nil :failure.class/bogus :bogus "" 0 [] {} false
                  :failure.class/unknown
                  :failure.class/timeout]]
      (doseq [input inputs]
        (is (sut/valid-class? (sut/safe-class input))
            (str "safe-class output must be a valid class for input: " input))))))

(deftest safe-class-is-idempotent
  (testing "safe-class is idempotent: applying it twice gives same result"
    (doseq [cls all-expected-classes]
      (is (= (sut/safe-class cls)
             (sut/safe-class (sut/safe-class cls)))
          (str "safe-class must be idempotent for " cls)))
    ;; Also idempotent on unknowns: safe-class(safe-class(nil)) = safe-class(nil)
    (is (= (sut/safe-class nil)
           (sut/safe-class (sut/safe-class nil))))))

(deftest safe-class-is-deterministic
  (testing "safe-class always returns the same result for the same input (pure fn)"
    (doseq [cls all-expected-classes]
      (is (= (sut/safe-class cls) (sut/safe-class cls))))
    (is (= (sut/safe-class nil) (sut/safe-class nil)))
    (is (= (sut/safe-class :bogus) (sut/safe-class :bogus)))))

;;==============================================================================
;; SECTION 5 — sli-incident? (conservative-default contract)
;;==============================================================================

(deftest sli-incident-returns-true-for-all-sli-impacting-classes
  (testing "sli-incident? returns true for all 8 SLI-impacting classes"
    (doseq [cls sli-impacting-classes]
      (is (true? (sut/sli-incident? cls))
          (str cls " should be an SLI incident")))))

(deftest sli-incident-returns-false-for-non-sli-classes
  (testing "sli-incident? returns false for task-code and invalid-input"
    (is (false? (sut/sli-incident? :failure.class/task-code)))
    (is (false? (sut/sli-incident? :failure.class/invalid-input)))))

(deftest sli-incident-returns-true-for-nil-conservative-default
  (testing "sli-incident? returns true for nil (conservative — never silently drop)"
    (is (true? (sut/sli-incident? nil)))))

(deftest sli-incident-returns-true-for-unknown-keyword
  (testing "sli-incident? returns true for unrecognised keyword (conservative)"
    (is (true? (sut/sli-incident? :failure.class/bogus)))
    (is (true? (sut/sli-incident? :bogus)))))

(deftest sli-incident-returns-true-for-unknown-class
  (testing "sli-incident? returns true for :failure.class/unknown (meta-reliability)"
    (is (true? (sut/sli-incident? :failure.class/unknown)))))

(deftest sli-incident-returns-true-for-non-keyword-inputs
  (testing "sli-incident? conservatively returns true for non-keyword inputs"
    (is (true? (sut/sli-incident? 0)))
    (is (true? (sut/sli-incident? "")))
    (is (true? (sut/sli-incident? [])))
    (is (true? (sut/sli-incident? {})))
    (is (true? (sut/sli-incident? 42)))
    (is (true? (sut/sli-incident? false)))))

(deftest sli-incident-returns-boolean-not-truthy
  (testing "sli-incident? always returns exactly a Boolean"
    (doseq [cls all-expected-classes]
      (is (instance? Boolean (sut/sli-incident? cls))
          (str cls " must return Boolean")))
    (is (instance? Boolean (sut/sli-incident? nil)))
    (is (instance? Boolean (sut/sli-incident? :bogus)))))

(deftest sli-incident-partitions-classes-correctly
  (testing "exactly 8 canonical classes are SLI incidents, 2 are not"
    (let [all      (set sut/all-classes)
          is-sli   (into #{} (filter sut/sli-incident? all))
          not-sli  (into #{} (remove sut/sli-incident? all))]
      (is (= 8 (count is-sli)))
      (is (= 2 (count not-sli)))
      (is (= sli-impacting-classes is-sli))
      (is (= non-sli-classes not-sli)))))

(deftest sli-incident-is-deterministic
  (testing "sli-incident? returns the same result for repeated calls"
    (doseq [cls all-expected-classes]
      (is (= (sut/sli-incident? cls) (sut/sli-incident? cls))))
    (is (= (sut/sli-incident? nil) (sut/sli-incident? nil)))))

;;==============================================================================
;; SECTION 6 — retry-hint (safe fallback contract)
;;==============================================================================

(deftest retry-hint-returns-correct-hint-for-all-classes
  (testing "retry-hint returns the canonical hint for each class"
    (are [cls expected] (= expected (sut/retry-hint cls))
      :failure.class/agent-backend       :retry-with-fresh-context
      :failure.class/task-code           :repair-and-retry
      :failure.class/external            :retry-with-backoff
      :failure.class/timeout             :retry-with-extended-timeout
      :failure.class/resource-exhaustion :wait-for-budget-reset
      :failure.class/network             :retry-with-backoff
      :failure.class/auth                :fix-credentials-then-retry
      :failure.class/invalid-input       :fix-input-then-retry
      :failure.class/internal            :escalate-to-human
      :failure.class/unknown             :escalate-to-human)))

(deftest retry-hint-returns-escalate-for-nil
  (testing "retry-hint returns :escalate-to-human for nil (safest unknown action)"
    (is (= :escalate-to-human (sut/retry-hint nil)))))

(deftest retry-hint-returns-escalate-for-unknown-keyword
  (testing "retry-hint returns :escalate-to-human for unrecognised keyword"
    (is (= :escalate-to-human (sut/retry-hint :failure.class/bogus)))
    (is (= :escalate-to-human (sut/retry-hint :bogus)))))

(deftest retry-hint-returns-escalate-for-non-keyword-inputs
  (testing "retry-hint returns :escalate-to-human for non-keyword inputs"
    (is (= :escalate-to-human (sut/retry-hint 0)))
    (is (= :escalate-to-human (sut/retry-hint "")))
    (is (= :escalate-to-human (sut/retry-hint [])))
    (is (= :escalate-to-human (sut/retry-hint {})))
    (is (= :escalate-to-human (sut/retry-hint false)))))

(deftest retry-hint-never-returns-nil
  (testing "retry-hint never returns nil — safe fallback contract"
    (let [inputs (concat all-expected-classes
                         [nil :bogus :failure.class/bogus "" 0 [] {}])]
      (doseq [input inputs]
        (is (some? (sut/retry-hint input))
            (str "retry-hint must not return nil for input: " input))))))

(deftest retry-hint-output-always-valid-hint
  (testing "retry-hint output is always one of the 8 canonical hint keywords"
    (let [inputs (concat all-expected-classes [nil :bogus 0 "" []])]
      (doseq [input inputs]
        (is (contains? known-retry-hints (sut/retry-hint input))
            (str "retry-hint output is not a known hint for input: " input))))))

(deftest retry-hint-is-deterministic
  (testing "retry-hint always returns the same result for the same input"
    (doseq [cls all-expected-classes]
      (is (= (sut/retry-hint cls) (sut/retry-hint cls))))
    (is (= (sut/retry-hint nil) (sut/retry-hint nil)))
    (is (= (sut/retry-hint :bogus) (sut/retry-hint :bogus)))))

;;==============================================================================
;; SECTION 7 — Cross-cutting invariants (N1 §5.3.3 determinism guarantee)
;;==============================================================================

(deftest taxonomy-is-immutable-vector
  (testing "taxonomy is a Clojure persistent (immutable) vector"
    (is (instance? clojure.lang.PersistentVector sut/taxonomy))))

(deftest all-classes-is-immutable-set
  (testing "all-classes is a Clojure persistent (immutable) set"
    (is (instance? clojure.lang.IPersistentSet sut/all-classes))))

(deftest class->entry-is-immutable-map
  (testing "class->entry is a Clojure persistent (immutable) map"
    (is (instance? clojure.lang.IPersistentMap sut/class->entry))))

(deftest class->retry-hint-is-immutable-map
  (testing "class->retry-hint is a Clojure persistent (immutable) map"
    (is (instance? clojure.lang.IPersistentMap sut/class->retry-hint))))

(deftest class->sli-impact-is-immutable-map
  (testing "class->sli-impact is a Clojure persistent (immutable) map"
    (is (instance? clojure.lang.IPersistentMap sut/class->sli-impact))))

(deftest no-lookup-table-is-nil
  (testing "none of the exported vars are nil"
    (is (some? sut/taxonomy))
    (is (some? sut/all-classes))
    (is (some? sut/class->entry))
    (is (some? sut/class->retry-hint))
    (is (some? sut/class->sli-impact))))

(deftest safe-class-then-valid-class-always-true
  (testing "safe-class output always satisfies valid-class? (composition invariant)"
    (let [test-values (concat all-expected-classes
                              [nil :bogus :failure.class/bogus 0 "" [] {}])]
      (doseq [v test-values]
        (is (true? (sut/valid-class? (sut/safe-class v)))
            (str "valid-class?(safe-class(" v ")) must be true"))))))

(deftest safe-class-then-retry-hint-never-nil
  (testing "composing safe-class then retry-hint never returns nil"
    (let [test-values (concat all-expected-classes [nil :bogus 0 ""])]
      (doseq [v test-values]
        (is (some? (sut/retry-hint (sut/safe-class v)))
            (str "retry-hint(safe-class(" v ")) must not be nil"))))))

(deftest safe-class-then-sli-incident-never-nil
  (testing "composing safe-class then sli-incident? never returns nil"
    (let [test-values (concat all-expected-classes [nil :bogus 0 ""])]
      (doseq [v test-values]
        (is (some? (sut/sli-incident? (sut/safe-class v)))
            (str "sli-incident?(safe-class(" v ")) must not be nil"))))))

(deftest all-derived-tables-agree-with-each-other
  (testing "class->retry-hint and class->entry :retry-hint are consistent"
    (doseq [cls all-expected-classes]
      (is (= (get sut/class->retry-hint cls)
             (:retry-hint (get sut/class->entry cls)))
          (str "retry-hint inconsistency for " cls))))
  (testing "class->sli-impact and class->entry :sli-impact are consistent"
    (doseq [cls all-expected-classes]
      (is (= (get sut/class->sli-impact cls)
             (:sli-impact (get sut/class->entry cls)))
          (str "sli-impact inconsistency for " cls)))))

(deftest unknown-catch-all-is-reachable-and-correct
  (testing ":failure.class/unknown is accessible via all lookup tables"
    (is (contains? sut/all-classes :failure.class/unknown))
    (is (contains? sut/class->entry :failure.class/unknown))
    (is (contains? sut/class->retry-hint :failure.class/unknown))
    (is (contains? sut/class->sli-impact :failure.class/unknown)))
  (testing ":failure.class/unknown has the sentinel catch-all pattern '.*'"
    (let [patterns (get-in sut/class->entry [:failure.class/unknown :heuristics :error-message-patterns])]
      (is (some #(= ".*" %) patterns)
          "unknown entry must contain '.*' catch-all pattern")))
  (testing ":failure.class/unknown error-codes contains UNKNOWN"
    (let [codes (get-in sut/class->entry [:failure.class/unknown :heuristics :error-codes])]
      (is (some #(= "UNKNOWN" %) codes)
          "unknown entry must contain 'UNKNOWN' error code"))))
