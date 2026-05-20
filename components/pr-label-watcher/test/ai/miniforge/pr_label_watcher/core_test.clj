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

(ns ai.miniforge.pr-label-watcher.core-test
  "Pure-logic tests for the M2 label-action matcher.

   The matcher must be entirely deterministic — every branch tested
   without shell or filesystem access by passing in stub functions for
   `git merge-base --is-ancestor`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-label-watcher.core :as sut]))

;------------------------------------------------------------------------------ Fixtures

(def ^:private dogfood-fix-action
  {:action       :rebase-and-resume
   :description  "Pause, rebase the task worktree onto post-merge main, resume from the next phase boundary"
   :on-conflict  :attention-required
   :applies-when {:workflow.base-sha/not-ancestor-of-merge true}})

(def ^:private test-registry
  {"dogfood-fix" dogfood-fix-action})

(def ^:private wf-old
  {:workflow/id #uuid "11111111-1111-1111-1111-111111111111"
   :workflow/base-sha "old-sha"})

(def ^:private wf-recent
  {:workflow/id #uuid "22222222-2222-2222-2222-222222222222"
   :workflow/base-sha "recent-sha"})

(def ^:private wf-no-base-sha
  ;; A pre-M1 workflow whose checkpoint never recorded a base SHA.
  ;; Must never match — ancestry can't be evaluated.
  {:workflow/id #uuid "33333333-3333-3333-3333-333333333333"})

(defn- merge-event
  ([labels]            (merge-event labels "merge-sha-abc"))
  ([labels merge-sha]  {:pr/number    42
                         :pr/labels    (set labels)
                         :pr/merge-sha merge-sha}))

(defn- ancestry-table-fn
  "Stub ancestry predicate driven by a `{[base merge] bool}` table —
   `true` means base IS an ancestor of merge (workflow already has the
   fix → skip)."
  [table]
  (fn [base-sha merge-sha]
    (boolean (get table [base-sha merge-sha]))))

;------------------------------------------------------------------------------ Layer 0: load-registry

(deftest load-registry-reads-m1-resource-test
  (testing "the registry resource ships under config/pr-lifecycle and parses"
    (let [r (sut/load-registry)]
      (is (map? r))
      (is (contains? r "dogfood-fix")
          "M1 seeded the dogfood-fix entry; this test fails if the resource is moved or renamed"))))

(deftest load-registry-action-shape-test
  (testing "the dogfood-fix entry has the keys the watcher reads"
    (let [action (get (sut/load-registry) "dogfood-fix")]
      (is (= :rebase-and-resume (:action action)))
      (is (true? (get-in action [:applies-when :workflow.base-sha/not-ancestor-of-merge]))
          ":applies-when predicate must be true so workflow-applies? fires when ancestry says NO"))))

;------------------------------------------------------------------------------ Layer 0: matching-labels

(deftest matching-labels-returns-intersection-test
  (testing "string set intersection between PR labels and registry keys"
    (is (= #{"dogfood-fix"}
           (sut/matching-labels #{"dogfood-fix" "other"} test-registry)))))

(deftest matching-labels-empty-when-no-overlap-test
  (is (= #{} (sut/matching-labels #{"other" "unrelated"} test-registry))))

(deftest matching-labels-case-sensitive-test
  (testing "GitHub labels are case-sensitive — no normalization"
    (is (= #{} (sut/matching-labels #{"Dogfood-Fix"} test-registry))
        "uppercase variant must NOT match — labels are normalized at GitHub-project boundary, not here")))

;------------------------------------------------------------------------------ Layer 0: workflow-applies?

(deftest workflow-applies?-true-when-base-not-ancestor-test
  (testing "workflow whose base SHA is NOT an ancestor of merge SHA matches"
    (let [ancestor?-fn (ancestry-table-fn {})] ; nothing ancestor of anything
      (is (true? (sut/workflow-applies? wf-old dogfood-fix-action
                                        ancestor?-fn "merge-sha"))))))

(deftest workflow-applies?-false-when-base-is-ancestor-test
  (testing "workflow whose base SHA IS an ancestor of merge SHA does NOT match (already has fix)"
    (let [ancestor?-fn (ancestry-table-fn {["recent-sha" "merge-sha"] true})]
      (is (false? (sut/workflow-applies? wf-recent dogfood-fix-action
                                         ancestor?-fn "merge-sha"))))))

(deftest workflow-applies?-false-when-no-base-sha-test
  (testing "workflow with no recorded base SHA is never matched (pre-M1 checkpoint, handled manually)"
    (let [ancestor?-fn (ancestry-table-fn {})]
      (is (false? (sut/workflow-applies? wf-no-base-sha dogfood-fix-action
                                         ancestor?-fn "merge-sha"))))))

(deftest workflow-applies?-false-when-applies-when-unset-test
  (testing "action without :applies-when never matches — opt-in semantics"
    (let [action {:action :rebase-and-resume :applies-when {}}
          ancestor?-fn (ancestry-table-fn {})]
      (is (false? (sut/workflow-applies? wf-old action
                                         ancestor?-fn "merge-sha"))))))

;------------------------------------------------------------------------------ Layer 1: match (top-level)

(deftest match-empty-vector-when-no-labels-match-test
  (testing "PR with no registry-labels produces no payloads"
    (is (= [] (sut/match (merge-event #{"unrelated"})
                         test-registry
                         [wf-old]
                         (ancestry-table-fn {}))))))

(deftest match-payload-shape-test
  (testing "match payload contains all the keys the meta-agent needs"
    (let [[payload :as payloads]
          (sut/match (merge-event #{"dogfood-fix"})
                     test-registry
                     [wf-old]
                     (ancestry-table-fn {}))]
      (is (= 1 (count payloads))
          "one matching label → one payload")
      (is (= 42                       (:pr/number payload)))
      (is (= #{"dogfood-fix"}         (:pr/labels payload)))
      (is (= "merge-sha-abc"          (:pr/merge-sha payload)))
      (is (= "dogfood-fix"            (:pr-label/label payload)))
      (is (= :rebase-and-resume       (:pr-label/action payload)))
      (is (= dogfood-fix-action       (:pr-label/config payload)))
      (is (vector? (:matched-workflows payload)))
      (is (= 1 (count (:matched-workflows payload)))))))

(deftest match-includes-only-applicable-workflows-test
  (testing "match filters out workflows that already have the fix (base IS ancestor)"
    (let [[payload] (sut/match (merge-event #{"dogfood-fix"})
                               test-registry
                               [wf-old wf-recent wf-no-base-sha]
                               (ancestry-table-fn {["recent-sha" "merge-sha-abc"] true}))]
      (is (= 1 (count (:matched-workflows payload)))
          "wf-old matches (not ancestor), wf-recent skipped (ancestor), wf-no-base-sha skipped (no base)")
      (is (= (:workflow/id wf-old)
             (-> payload :matched-workflows first :workflow/id))))))

(deftest match-emits-empty-matched-workflows-when-all-skip-test
  (testing "label matched but every workflow already has the fix → empty matched-workflows, payload still emitted"
    ;; Observability: operator sees \"label fired but nothing to do\".
    (let [[payload] (sut/match (merge-event #{"dogfood-fix"})
                               test-registry
                               [wf-recent]
                               (ancestry-table-fn {["recent-sha" "merge-sha-abc"] true}))]
      (is (some? payload)
          "payload IS emitted on label-match-but-no-applicable-workflows")
      (is (= [] (:matched-workflows payload))))))

(deftest match-handles-multiple-labels-test
  (testing "two matching labels produce two payloads, deterministically sorted"
    (let [registry {"a-fix"   (assoc dogfood-fix-action :action :rebase-and-resume)
                    "b-fix"   (assoc dogfood-fix-action :action :rebase-and-resume)}
          payloads (sut/match (merge-event #{"a-fix" "b-fix"})
                              registry
                              [wf-old]
                              (ancestry-table-fn {}))]
      (is (= 2 (count payloads)))
      (is (= ["a-fix" "b-fix"]
             (map :pr-label/label payloads))
          "labels are sorted for deterministic ordering across runs"))))

;------------------------------------------------------------------------------ Layer 1: ancestor predicate factory

(deftest make-ancestor?-fn-returns-false-on-nil-shas-test
  (testing "nil base-sha or merge-sha never claims ancestry — protects against pre-M1 workflows"
    (let [pred (sut/make-ancestor?-fn
                {:shell-fn (fn [& _] (throw (ex-info "should not shell out" {})))})]
      (is (false? (pred nil "merge-sha")))
      (is (false? (pred "base-sha" nil)))
      (is (false? (pred nil nil))))))

(deftest make-ancestor?-fn-treats-exit-0-as-ancestor-test
  (testing "shell exit 0 → ancestor; anything else → not ancestor"
    (let [pred-yes (sut/make-ancestor?-fn {:shell-fn (fn [& _] {:exit 0})})
          pred-no  (sut/make-ancestor?-fn {:shell-fn (fn [& _] {:exit 1})})]
      (is (true?  (pred-yes "a" "b")))
      (is (false? (pred-no  "a" "b"))))))

(deftest make-ancestor?-fn-treats-unknown-exit-as-not-ancestor-test
  (testing "non-{0,1} exits (e.g. 128 unknown SHA) treated as not-ancestor — conservative: surface the action"
    ;; Better to fire a redundant intervention than silently swallow a real
    ;; one because git couldn't resolve the SHA pair.
    (let [pred (sut/make-ancestor?-fn {:shell-fn (fn [& _] {:exit 128})})]
      (is (false? (pred "unknown-base" "unknown-merge"))))))

(deftest make-ancestor?-fn-throws-when-shell-fn-missing-test
  (testing "misconfigured opts (no :shell-fn) fail loudly at build time, not opaquely on first call"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":shell-fn"
                          (sut/make-ancestor?-fn {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":shell-fn"
                          (sut/make-ancestor?-fn {:shell-fn nil}))
        "explicit nil is rejected — caller probably forgot to wire the dep")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":shell-fn"
                          (sut/make-ancestor?-fn {:shell-fn "not-a-fn"}))
        "non-fn value rejected — fail fast at predicate-build time")))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.pr-label-watcher.core-test)
  :leave-this-here)
