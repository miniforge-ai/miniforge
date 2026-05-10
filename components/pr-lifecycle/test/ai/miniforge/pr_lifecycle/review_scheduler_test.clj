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

(ns ai.miniforge.pr-lifecycle.review-scheduler-test
  "Tests for the review-scheduler — marker detection, predicate,
   partition. Excludes the bracketing `with-pr-worktree` (covered
   by integration smoke against a real repo, not unit-tested here)."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.github :as github]
            [ai.miniforge.pr-lifecycle.review-scheduler :as sched]))

;; ── helpers ──────────────────────────────────────────────────────────

(defn- capture-shell
  [calls-atom result]
  (fn [opts & args]
    (swap! calls-atom conj {:opts opts :args (vec args)})
    result))

(def ^:private sha-a "a000000000000000000000000000000000000001")
(def ^:private sha-b "b000000000000000000000000000000000000002")
(def ^:private sha-c "c000000000000000000000000000000000000003")

(defn- review [sha author body]
  {:id (rand-int 100000)
   :commit_id sha
   :user {:login author}
   :body body})

(def ^:private mixed-reviews-json
  (json/generate-string
   [(review sha-a "miniforge-bot" (str "policy-review: 3 violations\n\n" github/review-marker))
    (review sha-b "human-reviewer" "looks good, ship it")
    (review sha-a "human-reviewer" "+1")
    (review sha-c "miniforge-bot" (str "policy-review: 0 violations\n\n" github/review-marker))]))

;; ── existing-review-shas ─────────────────────────────────────────────

(deftest existing-review-shas-filters-by-marker
  (testing "only reviews carrying review-marker contribute SHAs"
    (let [stub (capture-shell (atom [])
                              {:exit 0 :out mixed-reviews-json :err ""})]
      (with-redefs [process/shell stub]
        (let [r (sched/existing-review-shas "/some/repo" 42)]
          (is (dag/ok? r))
          (is (= #{sha-a sha-c} (:data r))
              "SHAs are union of all marker-bearing reviews; non-marker reviews ignored")))))
  (testing "no reviews → empty set, still ok"
    (let [stub (capture-shell (atom []) {:exit 0 :out "[]" :err ""})]
      (with-redefs [process/shell stub]
        (let [r (sched/existing-review-shas "/some/repo" 42)]
          (is (dag/ok? r))
          (is (= #{} (:data r)))))))
  (testing "blank stdout → empty set, still ok (gh paginate edge)"
    (let [stub (capture-shell (atom []) {:exit 0 :out "" :err ""})]
      (with-redefs [process/shell stub]
        (let [r (sched/existing-review-shas "/some/repo" 42)]
          (is (dag/ok? r))
          (is (= #{} (:data r))))))))

(deftest existing-review-shas-handles-paginate-concat
  (testing "gh --paginate emits multiple JSON arrays back-to-back; we concat them"
    (let [page1 [(review sha-a "x" (str "p1\n" github/review-marker))]
          page2 [(review sha-b "y" "no marker")
                 (review sha-c "x" (str "p2\n" github/review-marker))]
          raw   (str (json/generate-string page1) (json/generate-string page2))
          stub  (capture-shell (atom []) {:exit 0 :out raw :err ""})]
      (with-redefs [process/shell stub]
        (let [r (sched/existing-review-shas "/some/repo" 42)]
          (is (dag/ok? r))
          (is (= #{sha-a sha-c} (:data r))))))))

(deftest existing-review-shas-shells-correct-endpoint
  (testing "shells out to `gh api repos/{owner}/{repo}/pulls/<n>/reviews --paginate` in worktree dir"
    (let [calls (atom [])
          stub  (capture-shell calls {:exit 0 :out "[]" :err ""})]
      (with-redefs [process/shell stub]
        (sched/existing-review-shas "/some/repo" 42))
      (let [call (first @calls)]
        (is (= ["gh" "api"
                "repos/{owner}/{repo}/pulls/42/reviews"
                "--paginate"]
               (:args call)))
        (is (= "/some/repo" (str (get-in call [:opts :dir]))))))))

(deftest existing-review-shas-gh-failure-bubbles-up
  (testing "non-zero gh exit → typed :gh-command-failed"
    (let [stub (capture-shell (atom [])
                              {:exit 1 :out "" :err "HTTP 401"})]
      (with-redefs [process/shell stub]
        (let [r (sched/existing-review-shas "/some/repo" 42)]
          (is (not (dag/ok? r)))
          (is (= :gh-command-failed (get-in r [:error :code]))))))))

;; ── predicates ───────────────────────────────────────────────────────

(deftest pr-needs-review-honors-existing-shas
  (testing "true when pr/sha not in existing-shas"
    (is (true? (sched/pr-needs-review? {:pr/sha sha-a} #{sha-b sha-c})))
    (is (true? (sched/pr-needs-review? {:pr/sha sha-a} #{}))
        "empty set ⇒ always needs review")
    (is (true? (sched/pr-needs-review? {:pr/sha sha-a} nil))
        "nil set is treated as empty"))
  (testing "false when pr/sha is in existing-shas"
    (is (false? (sched/pr-needs-review? {:pr/sha sha-a} #{sha-a sha-b})))
    (is (false? (sched/pr-needs-review? {:pr/sha sha-c} #{sha-c}))))
  (testing "false when :pr/sha is missing or wrong type"
    (is (false? (sched/pr-needs-review? {:pr/sha nil} #{})))
    (is (false? (sched/pr-needs-review? {} #{})))
    (is (false? (sched/pr-needs-review? {:pr/sha 42} #{})))))

(deftest partition-needs-review-splits-prs
  (testing "splits into :needs-review / :already-reviewed by PR-keyed map"
    (let [prs [{:pr/number 1 :pr/sha sha-a}
               {:pr/number 2 :pr/sha sha-b}
               {:pr/number 3 :pr/sha sha-c}]
          existing {1 #{sha-a}                  ; pr 1 already reviewed
                    2 #{}                       ; pr 2 needs review
                    ;; pr 3: missing from map ⇒ defaults to #{} ⇒ needs review
                    }
          {:keys [needs-review already-reviewed]}
          (sched/partition-needs-review prs existing)]
      (is (= 2 (count needs-review)))
      (is (= #{2 3} (set (map :pr/number needs-review))))
      (is (= 1 (count already-reviewed)))
      (is (= [1] (mapv :pr/number already-reviewed))))))
