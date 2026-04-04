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

(ns ai.miniforge.tui-views.persistence.pr-cache-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.persistence.pr-cache :as cache])
  (:import
   [java.io File]))

;; ---------------------------------------------------------------------------
;; Test data
;; ---------------------------------------------------------------------------

(def pr-1
  {:pr/repo "acme/api" :pr/number 42
   :pr/additions 10 :pr/deletions 5
   :pr/status :open :pr/ci-status :success
   :pr/head-sha "abc123"})

(def pr-2
  {:pr/repo "acme/web" :pr/number 7
   :pr/additions 20 :pr/deletions 3
   :pr/status :open :pr/ci-status :pending
   :pr/head-sha "def456"})

(def pr-1-modified
  "Same repo/number as pr-1, but different fingerprint fields."
  (assoc pr-1 :pr/additions 99 :pr/head-sha "changed"))

;; ---------------------------------------------------------------------------
;; Temp directory helper
;; ---------------------------------------------------------------------------

(defn make-temp-dir
  "Create a temp directory for cache tests."
  []
  (let [f (File/createTempFile "pr-cache-test" ".d")]
    (.delete f)
    (.mkdirs f)
    f))

(defn cleanup-temp-dir
  "Recursively delete a temp directory."
  [^File dir]
  (doseq [f (reverse (file-seq dir))]
    (.delete f)))

;; ---------------------------------------------------------------------------
;; Layer 0: Fingerprint + entry helpers
;; ---------------------------------------------------------------------------

(deftest pr-fingerprint-test
  (testing "pr-fingerprint returns expected keys"
    (let [fp (cache/pr-fingerprint pr-1)]
      (is (= #{:additions :deletions :status :ci-status :head-sha}
             (set (keys fp))))
      (is (= 10 (:additions fp)))
      (is (= 5 (:deletions fp)))
      (is (= :open (:status fp)))
      (is (= :success (:ci-status fp)))
      (is (= "abc123" (:head-sha fp))))))

(deftest entry-valid?-test
  (testing "returns true when fingerprints match"
    (let [entry (cache/cache-entry pr-1)]
      (is (cache/entry-valid? entry pr-1))))

  (testing "returns false when fingerprints differ"
    (let [entry (cache/cache-entry pr-1)]
      (is (not (cache/entry-valid? entry pr-1-modified))))))

;; ---------------------------------------------------------------------------
;; Layer 2: apply-cached-policy
;; ---------------------------------------------------------------------------

(deftest apply-cached-policy-test
  (testing "applies cached policy to PRs without :pr/policy"
    (let [entry (assoc (cache/cache-entry pr-1)
                       :policy {:gate :pass :reason "all good"})
          the-cache {(cache/pr-cache-key pr-1) entry}
          result (cache/apply-cached-policy [pr-1] the-cache)]
      (is (= {:gate :pass :reason "all good"}
             (:pr/policy (first result))))))

  (testing "skips PRs that already have :pr/policy"
    (let [pr-with-policy (assoc pr-1 :pr/policy {:gate :fail :reason "nope"})
          entry (assoc (cache/cache-entry pr-1)
                       :policy {:gate :pass :reason "cached"})
          the-cache {(cache/pr-cache-key pr-1) entry}
          result (cache/apply-cached-policy [pr-with-policy] the-cache)]
      (is (= {:gate :fail :reason "nope"}
             (:pr/policy (first result))))))

  (testing "skips entries with mismatched fingerprints"
    (let [entry (assoc (cache/cache-entry pr-1)
                       :policy {:gate :pass :reason "stale"})
          the-cache {(cache/pr-cache-key pr-1-modified) entry}
          result (cache/apply-cached-policy [pr-1-modified] the-cache)]
      (is (nil? (:pr/policy (first result)))))))

;; ---------------------------------------------------------------------------
;; Layer 2: apply-cached-agent-risk
;; ---------------------------------------------------------------------------

(deftest apply-cached-agent-risk-test
  (testing "extracts risk from matching cache entries"
    (let [risk {:level :high :summary "danger"}
          entry (assoc (cache/cache-entry pr-1)
                       :agent-risk risk)
          the-cache {(cache/pr-cache-key pr-1) entry}
          result (cache/apply-cached-agent-risk [pr-1] the-cache)]
      (is (= {(cache/pr-cache-key pr-1) risk} result))))

  (testing "skips entries with mismatched fingerprints"
    (let [risk {:level :low :summary "fine"}
          entry (assoc (cache/cache-entry pr-1)
                       :agent-risk risk)
          the-cache {(cache/pr-cache-key pr-1-modified) entry}
          result (cache/apply-cached-agent-risk [pr-1-modified] the-cache)]
      (is (= {} result)))))

;; ---------------------------------------------------------------------------
;; Layer 1: read-cache / write-cache! roundtrip
;; ---------------------------------------------------------------------------

(deftest read-cache-nonexistent-test
  (testing "read-cache returns {} for non-existent file"
    (let [dir (make-temp-dir)]
      (try
        (is (= {} (cache/read-cache {:dir dir})))
        (finally
          (cleanup-temp-dir dir))))))

(deftest write-read-roundtrip-test
  (testing "write-cache! + read-cache roundtrip preserves data"
    (let [dir (make-temp-dir)
          entries {["acme/api" 42] {:fingerprint {:additions 10}
                                    :cached-at   12345
                                    :policy      {:gate :pass}}}]
      (try
        (cache/write-cache! entries {:dir dir})
        ;; write-cache! runs in a future; deref to ensure completion
        (Thread/sleep 100)
        (is (= entries (cache/read-cache {:dir dir})))
        (finally
          (cleanup-temp-dir dir))))))

;; ---------------------------------------------------------------------------
;; Layer 2: persist-policy-result!
;; ---------------------------------------------------------------------------

(deftest persist-policy-result!-test
  (testing "updates cache for a specific PR"
    (let [dir (make-temp-dir)
          prs [pr-1 pr-2]
          policy-result {:gate :pass :reason "lgtm"}]
      (try
        (cache/persist-policy-result!
         (cache/pr-cache-key pr-1) policy-result prs {:dir dir})
        (Thread/sleep 100)
        (let [c (cache/read-cache {:dir dir})
              entry (get c (cache/pr-cache-key pr-1))]
          (is (some? entry))
          (is (= policy-result (:policy entry)))
          (is (= (cache/pr-fingerprint pr-1) (:fingerprint entry)))
          ;; pr-2 should not be in cache
          (is (nil? (get c (cache/pr-cache-key pr-2)))))
        (finally
          (cleanup-temp-dir dir))))))

;; ---------------------------------------------------------------------------
;; Layer 2: persist-risk-triage!
;; ---------------------------------------------------------------------------

(deftest persist-risk-triage!-test
  (testing "updates cache for risk entries"
    (let [dir (make-temp-dir)
          prs [pr-1 pr-2]
          assessments {(cache/pr-cache-key pr-1) {:level :high :summary "risky"}
                       (cache/pr-cache-key pr-2) {:level :low :summary "safe"}}]
      (try
        (cache/persist-risk-triage! assessments prs {:dir dir})
        (Thread/sleep 100)
        (let [c (cache/read-cache {:dir dir})
              e1 (get c (cache/pr-cache-key pr-1))
              e2 (get c (cache/pr-cache-key pr-2))]
          (is (some? e1))
          (is (= {:level :high :summary "risky"} (:agent-risk e1)))
          (is (= (cache/pr-fingerprint pr-1) (:fingerprint e1)))
          (is (some? e2))
          (is (= {:level :low :summary "safe"} (:agent-risk e2)))
          (is (= (cache/pr-fingerprint pr-2) (:fingerprint e2))))
        (finally
          (cleanup-temp-dir dir))))))
