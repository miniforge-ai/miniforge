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

(ns ai.miniforge.pr-lifecycle.monitor-worklist-test
  "Tests for the PR monitor work-list namespace.

   I/O tests use `babashka.fs/with-temp-dir` for filesystem isolation.
   `fetch-pr-state` (private) is stubbed via `with-redefs` for prune tests."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.pr-lifecycle.monitor-worklist :as worklist]))

;------------------------------------------------------------------------------ Fixtures

(def ^:private known-url "https://github.com/miniforge-ai/miniforge.git")

(defn- make-pr-entry
  "Build a minimal valid WorklistPrEntry."
  [number repo]
  {:pr/url      (str "https://github.com/" repo "/pull/" number)
   :pr/number   number
   :pr/repo     repo
   :pr/added-at (java.util.Date.)})

(defn- make-entry
  "Build a minimal valid WorklistEntry for testing."
  ([]
   (make-entry [(make-pr-entry 42 "org/repo")]))
  ([prs]
   {:worklist/repo-key   "abc123def456"
    :worklist/prs        prs
    :worklist/updated-at (java.util.Date.)}))

(def ^:private open-pr   (make-pr-entry 1 "org/repo"))
(def ^:private merged-pr (make-pr-entry 2 "org/repo"))
(def ^:private closed-pr (make-pr-entry 3 "org/repo"))

;------------------------------------------------------------------------------ Layer 1: repo-key

(deftest repo-key-deterministic-test
  (testing "same URL always produces the same key"
    (is (= (worklist/repo-key known-url)
           (worklist/repo-key known-url))))

  (testing "different URLs produce different keys"
    (is (not= (worklist/repo-key known-url)
              (worklist/repo-key "https://github.com/other/repo.git"))))

  (testing "key length is exactly 12 characters"
    (is (= 12 (count (worklist/repo-key known-url)))))

  (testing "key is lowercase hex"
    (is (re-matches #"[0-9a-f]+" (worklist/repo-key known-url)))))

;------------------------------------------------------------------------------ Layer 1: worklist-path

(deftest worklist-path-test
  (testing "assembles path under pr-monitor subdir with .edn extension"
    (let [home "/home/user/.miniforge"
          rkey "abc123def456"
          path (worklist/worklist-path home rkey)]
      (is (str/includes? path "pr-monitor"))
      (is (str/includes? path rkey))
      (is (str/ends-with? path ".edn"))))

  (testing "path starts with home-dir"
    (let [home "/custom/home"
          path (worklist/worklist-path home "deadbeef1234")]
      (is (str/starts-with? path home)))))

;------------------------------------------------------------------------------ Layer 2: persist-worklist!

(deftest persist-worklist-happy-test
  (testing "creates dirs and writes EDN, returns schema/success"
    (fs/with-temp-dir [tmp {}]
      (let [rkey  "abc123def456"
            path  (worklist/worklist-path (str tmp) rkey)
            entry (make-entry)]
        (let [result (worklist/persist-worklist! path entry)]
          (is (schema/succeeded? result))
          (is (= entry (:worklist result)))
          (is (fs/exists? path)))))))

(deftest persist-worklist-invalid-entry-test
  (testing "returns schema/failure on schema mismatch, no file written"
    (fs/with-temp-dir [tmp {}]
      (let [path  (str (fs/path tmp "worklist.edn"))
            ;; Missing required :worklist/updated-at
            entry {:worklist/repo-key "abc123def456"
                   :worklist/prs      []}]
        (let [result (worklist/persist-worklist! path entry)]
          (is (schema/failed? result))
          (is (not (fs/exists? path))))))))

(deftest persist-worklist-io-error-test
  (testing "returns schema/failure when a file blocks directory creation"
    (fs/with-temp-dir [tmp {}]
      ;; Place a regular file where fs/create-dirs would need to create a dir
      (let [blocker (str (fs/path tmp "pr-monitor"))]
        (spit blocker "not-a-dir")
        (let [path   (str (fs/path tmp "pr-monitor" "abc123def456.edn"))
              entry  (make-entry)
              result (worklist/persist-worklist! path entry)]
          (is (schema/failed? result)))))))

;------------------------------------------------------------------------------ Layer 2: load-worklist

(deftest load-worklist-happy-test
  (testing "round-trips a persisted entry"
    (fs/with-temp-dir [tmp {}]
      (let [rkey  "abc123def456"
            path  (worklist/worklist-path (str tmp) rkey)
            entry (make-entry)]
        (worklist/persist-worklist! path entry)
        (let [result (worklist/load-worklist path)]
          (is (schema/succeeded? result))
          (is (= entry (:worklist result))))))))

(deftest load-worklist-missing-test
  (testing "returns schema/failure for a non-existent path"
    (let [result (worklist/load-worklist "/no/such/path/worklist.edn")]
      (is (schema/failed? result))
      (is (some? (:error result))))))

(deftest load-worklist-corrupt-edn-test
  (testing "returns schema/failure for unparseable EDN"
    (fs/with-temp-dir [tmp {}]
      (let [path (str (fs/path tmp "worklist.edn"))]
        (spit path "this is not valid EDN }{{{")
        (is (schema/failed? (worklist/load-worklist path))))))

  (testing "returns schema/failure for EDN that fails WorklistEntry schema"
    (fs/with-temp-dir [tmp {}]
      (let [path (str (fs/path tmp "worklist.edn"))]
        ;; Valid EDN but wrong shape
        (spit path (pr-str {:not-a-worklist true}))
        (is (schema/failed? (worklist/load-worklist path)))))))

;------------------------------------------------------------------------------ Layer 2: prune-closed-prs

(deftest prune-keeps-open-drops-merged-closed-test
  (testing "keeps OPEN entries, drops MERGED and CLOSED"
    (with-redefs [ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
                  (fn [{:pr/keys [number]}]
                    (case (int number)
                      1 "OPEN"
                      2 "MERGED"
                      3 "CLOSED"
                      "OPEN"))]
      (let [entry  (make-entry [open-pr merged-pr closed-pr])
            result (worklist/prune-closed-prs entry)]
        (is (not (anomaly/anomaly? result)))
        (is (= [open-pr] (:worklist/prs result))))))

  (testing "returns original map identity when all PRs are OPEN"
    (with-redefs [ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
                  (fn [_] "OPEN")]
      (let [entry  (make-entry [open-pr])
            result (worklist/prune-closed-prs entry)]
        (is (= entry result)))))

  (testing "bumps :worklist/updated-at when PRs are actually dropped (Copilot #1198)"
    (with-redefs [ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
                  (fn [{:pr/keys [number]}] (if (= 1 (int number)) "OPEN" "MERGED"))]
      (let [entry  (assoc (make-entry [open-pr merged-pr])
                          :worklist/updated-at #inst "2000-01-01T00:00:00.000-00:00")
            result (worklist/prune-closed-prs entry)]
        (is (= [open-pr] (:worklist/prs result)))
        (is (.after ^java.util.Date (:worklist/updated-at result)
                    (:worklist/updated-at entry))
            "dropping a PR must advance the last-write instant")))))

(deftest fetch-pr-state-tolerates-whitespace-json-test
  ;; Copilot #1198: gh's --json is compact today, but a pretty-printed
  ;; `"state": "OPEN"` (whitespace around the colon) must still parse.
  (testing "whitespace around the colon in gh JSON still extracts the state"
    (with-redefs [process/process (fn [& _] (future {:exit 0
                                                     :out "{\n  \"state\": \"OPEN\"\n}"
                                                     :err ""}))]
      (is (= "OPEN"
             (#'ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
              {:pr/number 1 :pr/repo "org/repo"})))))
  (testing "compact JSON still parses"
    (with-redefs [process/process (fn [& _] (future {:exit 0
                                                     :out "{\"state\":\"MERGED\"}"
                                                     :err ""}))]
      (is (= "MERGED"
             (#'ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
              {:pr/number 2 :pr/repo "org/repo"}))))))

(deftest prune-gh-failure-test
  (testing "returns anomaly when fetch-pr-state returns anomaly"
    (with-redefs [ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
                  (fn [pr]
                    (anomaly/anomaly :fault "gh failed" {:pr pr}))]
      (let [result (worklist/prune-closed-prs (make-entry [open-pr]))]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result))))))

  (testing "short-circuits on first failure, does not call gh for remaining PRs"
    (let [call-count (atom 0)]
      (with-redefs [ai.miniforge.pr-lifecycle.monitor-worklist/fetch-pr-state
                    (fn [_]
                      (swap! call-count inc)
                      (anomaly/anomaly :fault "gh failed" {}))]
        (worklist/prune-closed-prs (make-entry [open-pr merged-pr]))
        (is (= 1 @call-count))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.pr-lifecycle.monitor-worklist-test)
  :leave-this-here)
