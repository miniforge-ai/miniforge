;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.event-envelope-test
  "Tests for the event-envelope normalizer.

   Each deftest group verifies one behavioural contract:
     - Monotonic :seq assignment (including concurrency)
     - Field copying (:tool/id, :timestamp)
     - Input/output redaction (strings truncated, structures hashed, nil preserved)
     - :tool/error? passthrough
     - :tool/duration-ms passthrough
     - :resource/version-hash conditional attachment
     - Observation schema validity for every produced map"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [ai.miniforge.progress-detector.event-envelope :as sut]
   [ai.miniforge.progress-detector.schema :as schema]))

;------------------------------------------------------------------------------ Fixtures

(defn- reset-counter-fixture
  "Reset the per-runtime seq counter before each test so that seq=1
   on the first normalize call of every test, regardless of run order."
  [f]
  (sut/reset-counter!)
  (f))

(use-fixtures :each reset-counter-fixture)

;------------------------------------------------------------------------------ Factories

(def ^:private fixed-ts
  "Fixed Instant — avoids clock calls inside tests."
  (java.time.Instant/ofEpochMilli 1700000000000))

(defn- base-event
  "Minimal valid source event."
  []
  {:tool/id   :tool/Read
   :timestamp fixed-ts})

(defn- stable-profile
  "ToolProfile that triggers :resource/version-hash attachment."
  []
  {:tool/id     :tool/Read
   :determinism :stable-with-resource-version})

(defn- env-profile
  "ToolProfile that does NOT trigger :resource/version-hash attachment."
  []
  {:tool/id     :tool/Bash
   :determinism :environment-dependent})

;------------------------------------------------------------------------------ Layer 1
;; Monotonic :seq assignment

(deftest seq-starts-at-one-test
  (testing "first normalize call after reset returns :seq 1"
    (is (= 1 (:seq (sut/normalize (base-event)))))))

(deftest seq-is-monotonic-test
  (testing ":seq increments by 1 on each successive call"
    (let [obs1 (sut/normalize (base-event))
          obs2 (sut/normalize (base-event))
          obs3 (sut/normalize (base-event))]
      (is (= 1 (:seq obs1)))
      (is (= 2 (:seq obs2)))
      (is (= 3 (:seq obs3))))))

(deftest concurrent-seq-uniqueness-test
  (testing "seq values are unique under 50 concurrent normalize calls"
    ;; Atoms use compare-and-swap — this guards against regressions if the
    ;; counter is ever replaced with an unsynchronised primitive.
    (let [futures (doall (repeatedly 50 #(future (sut/normalize (base-event)))))
          seqs    (mapv #(:seq (deref %)) futures)]
      (is (= 50 (count (set seqs)))
          "every concurrent call must receive a distinct :seq value"))))

;------------------------------------------------------------------------------ Layer 1
;; Required field copying

(deftest copies-tool-id-test
  (testing ":tool/id is copied verbatim from source event"
    (let [obs (sut/normalize {:tool/id :tool/Write :timestamp fixed-ts})]
      (is (= :tool/Write (:tool/id obs))))))

(deftest copies-timestamp-test
  (testing ":timestamp is copied verbatim from source event"
    (let [obs (sut/normalize (base-event))]
      (is (= fixed-ts (:timestamp obs))))))

;------------------------------------------------------------------------------ Layer 1
;; Input redaction

(deftest short-string-input-passes-through-test
  (testing "string input at or below the limit is preserved verbatim"
    (let [obs (sut/normalize (assoc (base-event) :tool/input "ls -la"))]
      (is (= "ls -la" (:tool/input obs))))))

(deftest long-string-input-truncated-test
  (testing "string input longer than 240 chars is truncated with trailing ellipsis"
    (let [long-str (apply str (repeat 300 "a"))
          obs      (sut/normalize (assoc (base-event) :tool/input long-str))]
      (is (string? (:tool/input obs)))
      (is (<= (count (:tool/input obs)) 256))
      (is (str/ends-with? (:tool/input obs) "…")))))

(deftest structured-input-becomes-hash-test
  (testing "structured (non-string) input is replaced with 'hash:<hex>'"
    (let [obs (sut/normalize (assoc (base-event) :tool/input {:cmd "git" :args ["log"]}))]
      (is (string? (:tool/input obs)))
      (is (re-matches #"hash:[0-9a-f]+" (:tool/input obs))))))

(deftest nil-input-preserved-test
  (testing "nil :tool/input is preserved — key present with nil value"
    (let [obs (sut/normalize (assoc (base-event) :tool/input nil))]
      (is (contains? obs :tool/input))
      (is (nil? (:tool/input obs))))))

(deftest absent-input-not-injected-test
  (testing "absent :tool/input is not injected into result"
    (let [obs (sut/normalize (base-event))]
      (is (not (contains? obs :tool/input))))))

;------------------------------------------------------------------------------ Layer 1
;; Output redaction

(deftest short-string-output-passes-through-test
  (testing "string output at or below the limit is preserved verbatim"
    (let [obs (sut/normalize (assoc (base-event) :tool/output "(ns foo)"))]
      (is (= "(ns foo)" (:tool/output obs))))))

(deftest long-string-output-truncated-test
  (testing "string output longer than 240 chars is truncated with trailing ellipsis"
    (let [long-str (apply str (repeat 500 "z"))
          obs      (sut/normalize (assoc (base-event) :tool/output long-str))]
      (is (string? (:tool/output obs)))
      (is (<= (count (:tool/output obs)) 256))
      (is (str/ends-with? (:tool/output obs) "…")))))

(deftest structured-output-becomes-hash-test
  (testing "structured output is replaced with 'hash:<hex>'"
    (let [obs (sut/normalize (assoc (base-event) :tool/output [1 2 3]))]
      (is (string? (:tool/output obs)))
      (is (re-matches #"hash:[0-9a-f]+" (:tool/output obs))))))

;------------------------------------------------------------------------------ Layer 1
;; :tool/error? passthrough

(deftest error-true-passthrough-test
  (testing ":tool/error? true is passed through unchanged"
    (let [obs (sut/normalize (assoc (base-event) :tool/error? true))]
      (is (true? (:tool/error? obs))))))

(deftest error-false-passthrough-test
  (testing ":tool/error? false is passed through unchanged"
    (let [obs (sut/normalize (assoc (base-event) :tool/error? false))]
      (is (false? (:tool/error? obs))))))

(deftest absent-error-not-injected-test
  (testing "absent :tool/error? is not injected"
    (let [obs (sut/normalize (base-event))]
      (is (not (contains? obs :tool/error?))))))

;------------------------------------------------------------------------------ Layer 1
;; :tool/duration-ms passthrough

(deftest duration-ms-passthrough-test
  (testing ":tool/duration-ms is passed through when present"
    (let [obs (sut/normalize (assoc (base-event) :tool/duration-ms 150))]
      (is (= 150 (:tool/duration-ms obs))))))

(deftest absent-duration-not-injected-test
  (testing "absent :tool/duration-ms is not injected"
    (let [obs (sut/normalize (base-event))]
      (is (not (contains? obs :tool/duration-ms))))))

;------------------------------------------------------------------------------ Layer 1
;; :resource/version-hash conditional attachment

(deftest version-hash-attached-when-stable-profile-test
  (testing ":resource/version-hash is attached when profile determinism is :stable-with-resource-version"
    (let [event (assoc (base-event) :resource/version-hash "sha256:abc123")
          obs   (sut/normalize event (stable-profile))]
      (is (= "sha256:abc123" (:resource/version-hash obs))))))

(deftest version-hash-not-attached-for-env-dependent-profile-test
  (testing ":resource/version-hash is not attached when profile is :environment-dependent"
    (let [event (assoc (base-event) :resource/version-hash "sha256:abc123")
          obs   (sut/normalize event (env-profile))]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-for-unstable-profile-test
  (testing ":resource/version-hash is not attached when profile is :unstable"
    (let [event (assoc (base-event) :resource/version-hash "sha256:xyz")
          obs   (sut/normalize event {:tool/id :tool/Fetch :determinism :unstable})]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-when-absent-from-event-test
  (testing ":resource/version-hash not attached when event does not carry it"
    (let [obs (sut/normalize (base-event) (stable-profile))]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-without-profile-test
  (testing ":resource/version-hash not attached when tool-profile is nil"
    (let [event (assoc (base-event) :resource/version-hash "sha256:abc")
          obs   (sut/normalize event nil)]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-arity-one-test
  (testing ":resource/version-hash not attached via single-arity normalize"
    (let [event (assoc (base-event) :resource/version-hash "sha256:abc")
          obs   (sut/normalize event)]
      (is (not (contains? obs :resource/version-hash))))))

;------------------------------------------------------------------------------ Layer 2
;; Schema validity — every produced Observation must pass m/validate

(deftest all-outputs-pass-schema-test
  (testing "all normalized observations satisfy the Observation schema"
    (let [profiles [(stable-profile) (env-profile) nil]
          events   [(base-event)
                    (assoc (base-event)
                           :tool/input "short"
                           :tool/output "result"
                           :tool/error? false
                           :tool/duration-ms 42)
                    (assoc (base-event)
                           :tool/input (apply str (repeat 300 "x"))
                           :tool/output {:key "val"}
                           :tool/error? true)
                    (assoc (base-event)
                           :resource/version-hash "sha256:test123")
                    ;; nil :tool/input must also round-trip cleanly
                    (assoc (base-event) :tool/input nil)]]
      (doseq [e events p profiles]
        (let [obs (sut/normalize e p)]
          (is (true? (m/validate schema/Observation obs))
              (str "Observation invalid for event=" (pr-str e)
                   " profile=" (pr-str p))))))))

(deftest resource-version-hash-nil-is-valid-test
  (testing ":resource/version-hash nil is preserved and allowed by [:maybe :string]"
    (let [event (assoc (base-event) :resource/version-hash nil)
          obs   (sut/normalize event (stable-profile))]
      ;; Key must be present (not silently dropped by the normalizer)
      (is (contains? obs :resource/version-hash)
          "nil version-hash should be preserved, not dropped")
      ;; Value must be nil (not substituted)
      (is (nil? (:resource/version-hash obs)))
      ;; Schema must accept nil under [:maybe :string]
      (is (true? (m/validate schema/Observation obs))))))
