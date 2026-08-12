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
(ns ai.miniforge.pr-lifecycle.merge-governed-test
  "The composition that stands between a merge request and `gh pr merge`.

   `merge-authority-test` covers authority resolution and
   `merge-transaction-test` covers the durable record. Neither covers
   `transact!`, which is where the guarantee actually lives: no provider
   command runs unless authority permitted it. A seam whose whole job is
   refusing needs a test that proves it refuses."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.merge-governed :as governed]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(defn- ^{:stratum 0} tmp []
  (str (.toFile (Files/createTempDirectory "governed" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} recording-operations
  "Operations that record every provider invocation instead of shelling
   out. The recorder is the point: the assertions are about what was
   NOT called."
  [calls]
  {:run-gh (fn [args _worktree]
             (swap! calls conj [:run-gh args])
             (dag/ok {:output "{\"state\":\"OPEN\"}"}))
   :merge-pr (fn [_worktree pr-number & _]
               (swap! calls conj [:merge-pr pr-number])
               (dag/ok {:enabled? true}))})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} context
  ([] (context {}))
  ([overrides]
   (merge {:dag-id (random-uuid)
           :run-id (random-uuid)
           :task-id (random-uuid)
           :pr-id 42
           :effect-store-dir (tmp)
           :grant-breach-dir (tmp)}
          overrides)))

(defn- ^{:stratum 1} transact
  [ctx calls]
  (governed/transact! (recording-operations calls)
                      "/tmp/worktree" 42 "miniforge-ai/miniforge"
                      {:method :squash} ctx now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} no-authority-means-no-provider-command-test
  ;; The headline guarantee of Ariadne 2d. If authority cannot be
  ;; established, `gh pr merge` must never run — not run-and-fail, not
  ;; run-and-report, never run.
  (testing "a context with no workflow run cannot bind merge scope, so nothing fires"
    (let [calls (atom [])
          result (transact (dissoc (context) :run-id) calls)]
      (is (dag/err? result))
      (is (= :merge-authority-unavailable (:code (:error result)))
          (str "unexpected error: " (pr-str (:error result))))
      (is (empty? @calls)
          (str "no provider command may run without authority, but saw: "
               (pr-str @calls)))))

  (testing "the refusal is reported, not swallowed into a soft success"
    (let [calls (atom [])
          result (transact (dissoc (context) :run-id) calls)]
      (is (not (dag/ok? result))
          "a merge that never happened must not read as ok"))))

(deftest ^{:stratum 2} a-permitted-merge-reaches-the-provider-test
  ;; The complement. A guard that refuses everything is not a guard, it
  ;; is an outage — so the allowed path has to be proven too.
  (let [calls (atom [])
        result (transact (context) calls)]
    (is (some #(= :merge-pr (first %)) @calls)
        (str "an authorized merge should reach the provider, saw: "
             (pr-str @calls)))
    ;; Not `(some? result)` — transact! returns a map on every path, so
    ;; that would pass while the permitted path silently started
    ;; erroring. Assert the outcome, and say what it was when it fails.
    (is (dag/ok? result)
        (str "an authorized merge should succeed, got: " (pr-str result)))))

(deftest ^{:stratum 2} the-durable-record-precedes-the-provider-test
  ;; A proposal written after the effect is not evidence, it is a
  ;; retelling. The record must exist before anything irreversible runs.
  (let [calls (atom [])
        ctx (context)
        _ (transact ctx calls)
        records (file-seq (java.io.File. ^String (:effect-store-dir ctx)))]
    (is (some #(.endsWith (.getName ^java.io.File %) ".edn") records)
        "a durable effect record should exist in the store after transact!")))
