;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.progress-detector.event-envelope-test
  "Tests for the event-envelope normalizer.

   Each deftest builds its own normalizer via `make-normalizer` —
   no global counter, no cross-test coupling, and the first emitted
   :seq is 0 per the Stage 2 spec's per-agent-run requirement."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.progress-detector.event-envelope :as sut]
   [ai.miniforge.progress-detector.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Constants + factories

(def ^:private fixed-ts
  "Fixed Instant — avoids clock calls inside tests."
  (java.time.Instant/ofEpochMilli 1700000000000))

(def ^:private redaction-token-pattern
  "Regex matching the redacted-value token shape produced by the
   normalizer. Pulled to a constant so the tests document the
   contract instead of repeating the literal."
  #"hash:[0-9a-f]+:len[0-9]+")

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
;; Per-run :seq starts at 0

(deftest seq-starts-at-zero-test
  (testing "first emitted :seq is 0 per Stage 2 spec"
    (let [normalize (sut/make-normalizer)]
      (is (= 0 (:seq (normalize (base-event))))))))

(deftest seq-is-monotonic-test
  (testing ":seq increments by 1 on each successive call"
    (let [normalize (sut/make-normalizer)
          seqs      (vec (repeatedly 3 #(:seq (normalize (base-event)))))]
      (is (= [0 1 2] seqs)))))

(deftest concurrent-runs-do-not-interleave-test
  (testing "two normalizers each yield :seq 0 on first call"
    (let [normalize-a (sut/make-normalizer)
          normalize-b (sut/make-normalizer)]
      (is (= 0 (:seq (normalize-a (base-event)))))
      (is (= 0 (:seq (normalize-b (base-event)))))
      (is (= 1 (:seq (normalize-a (base-event)))))
      (is (= 1 (:seq (normalize-b (base-event))))))))

(deftest concurrent-seq-uniqueness-within-runner-test
  (testing "50 concurrent calls on one normalizer yield 50 distinct :seq values"
    (let [normalize (sut/make-normalizer)
          futures   (doall (repeatedly 50
                                       #(future (normalize (base-event)))))
          seqs      (mapv #(:seq (deref %)) futures)]
      (is (= 50 (count (set seqs)))
          "every concurrent call must receive a distinct :seq value"))))

;------------------------------------------------------------------------------ Layer 1
;; Required field copying

(deftest copies-tool-id-test
  (testing ":tool/id is copied verbatim from source event"
    (let [normalize (sut/make-normalizer)
          obs (normalize {:tool/id :tool/Write :timestamp fixed-ts})]
      (is (= :tool/Write (:tool/id obs))))))

(deftest copies-timestamp-test
  (testing ":timestamp is copied verbatim from source event"
    (let [normalize (sut/make-normalizer)
          obs (normalize (base-event))]
      (is (= fixed-ts (:timestamp obs))))))

;------------------------------------------------------------------------------ Layer 1
;; Input redaction — always non-reversible token (no raw content leaks)

(deftest short-string-input-is-hashed-test
  (testing "string input never preserves raw content — even short strings hash"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/input "ls -la"))]
      (is (string? (:tool/input obs)))
      (is (re-matches redaction-token-pattern (:tool/input obs))
          "short strings must not leak raw content")
      (is (not= "ls -la" (:tool/input obs))))))

(deftest long-string-input-is-hashed-test
  (testing "string input longer than 240 chars also hashes"
    (let [normalize (sut/make-normalizer)
          long-str (apply str (repeat 300 "a"))
          obs      (normalize (assoc (base-event) :tool/input long-str))]
      (is (re-matches redaction-token-pattern (:tool/input obs))))))

(deftest structured-input-becomes-hash-test
  (testing "structured (non-string) input is hashed too"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/input {:cmd "git" :args ["log"]}))]
      (is (re-matches redaction-token-pattern (:tool/input obs))))))

(deftest hash-token-encodes-length-test
  (testing "redacted token includes length suffix for diagnostic signal"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/input "abcdef"))
          token (:tool/input obs)]
      (is (clojure.string/ends-with? token ":len6")
          "redacted token must encode source length"))))

(deftest nil-input-preserved-test
  (testing "nil :tool/input is preserved — key present with nil value"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/input nil))]
      (is (contains? obs :tool/input))
      (is (nil? (:tool/input obs))))))

(deftest absent-input-not-injected-test
  (testing "absent :tool/input is not injected into result"
    (let [normalize (sut/make-normalizer)
          obs (normalize (base-event))]
      (is (not (contains? obs :tool/input))))))

;------------------------------------------------------------------------------ Layer 1
;; Output redaction

(deftest short-string-output-is-hashed-test
  (testing "string output is also redacted regardless of length"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/output "(ns foo)"))]
      (is (re-matches redaction-token-pattern (:tool/output obs))))))

(deftest structured-output-becomes-hash-test
  (testing "structured output is hashed too"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/output [1 2 3]))]
      (is (re-matches redaction-token-pattern (:tool/output obs))))))

;------------------------------------------------------------------------------ Layer 1
;; :tool/error? passthrough

(deftest error-true-passthrough-test
  (testing ":tool/error? true is passed through unchanged"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/error? true))]
      (is (true? (:tool/error? obs))))))

(deftest error-false-passthrough-test
  (testing ":tool/error? false is passed through unchanged"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/error? false))]
      (is (false? (:tool/error? obs))))))

(deftest absent-error-not-injected-test
  (testing "absent :tool/error? is not injected"
    (let [normalize (sut/make-normalizer)
          obs (normalize (base-event))]
      (is (not (contains? obs :tool/error?))))))

;------------------------------------------------------------------------------ Layer 1
;; :tool/duration-ms passthrough

(deftest duration-ms-passthrough-test
  (testing ":tool/duration-ms is passed through when present"
    (let [normalize (sut/make-normalizer)
          obs (normalize (assoc (base-event) :tool/duration-ms 150))]
      (is (= 150 (:tool/duration-ms obs))))))

(deftest absent-duration-not-injected-test
  (testing "absent :tool/duration-ms is not injected"
    (let [normalize (sut/make-normalizer)
          obs (normalize (base-event))]
      (is (not (contains? obs :tool/duration-ms))))))

;------------------------------------------------------------------------------ Layer 1
;; :resource/version-hash conditional attachment

(deftest version-hash-attached-when-stable-profile-test
  (testing ":resource/version-hash is attached when profile :determinism is :stable-with-resource-version"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash "sha256:abc123")
          obs   (normalize event (stable-profile))]
      (is (= "sha256:abc123" (:resource/version-hash obs))))))

(deftest version-hash-not-attached-for-env-dependent-profile-test
  (testing ":resource/version-hash is not attached for :environment-dependent"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash "sha256:abc123")
          obs   (normalize event (env-profile))]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-for-unstable-profile-test
  (testing ":resource/version-hash is not attached for :unstable"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash "sha256:xyz")
          obs   (normalize event {:tool/id :tool/Fetch :determinism :unstable})]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-when-absent-from-event-test
  (testing ":resource/version-hash not attached when event does not carry it"
    (let [normalize (sut/make-normalizer)
          obs (normalize (base-event) (stable-profile))]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-without-profile-test
  (testing ":resource/version-hash not attached when tool-profile is nil"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash "sha256:abc")
          obs   (normalize event nil)]
      (is (not (contains? obs :resource/version-hash))))))

(deftest version-hash-not-attached-arity-one-test
  (testing ":resource/version-hash not attached via single-arity normalize"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash "sha256:abc")
          obs   (normalize event)]
      (is (not (contains? obs :resource/version-hash))))))

;------------------------------------------------------------------------------ Layer 2
;; Schema validity — every produced Observation must pass m/validate

(deftest all-outputs-pass-schema-test
  (testing "all normalized observations satisfy the Observation schema"
    (let [normalize (sut/make-normalizer)
          profiles  [(stable-profile) (env-profile) nil]
          events    [(base-event)
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
                     (assoc (base-event) :tool/input nil)]]
      (doseq [e events p profiles]
        (let [obs (normalize e p)]
          (is (true? (m/validate schema/Observation obs))
              (str "Observation invalid for event=" (pr-str e)
                   " profile=" (pr-str p))))))))

(deftest resource-version-hash-nil-is-valid-test
  (testing ":resource/version-hash nil is preserved and allowed by [:maybe :string]"
    (let [normalize (sut/make-normalizer)
          event (assoc (base-event) :resource/version-hash nil)
          obs   (normalize event (stable-profile))]
      (is (contains? obs :resource/version-hash)
          "nil version-hash should be preserved, not dropped")
      (is (nil? (:resource/version-hash obs)))
      (is (true? (m/validate schema/Observation obs))))))
