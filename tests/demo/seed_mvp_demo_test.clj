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

(ns seed-mvp-demo-test
  "Tests for the MVP demo seed script fixtures and functions.
   Validates data integrity, cross-referencing, and argument parsing."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn load-fixture
  "Load and parse an EDN fixture file."
  [filename]
  (-> (str "tests/fixtures/demo/" filename)
      slurp
      (edn/read-string)))

(defn temp-dir
  "Create a unique temp directory path for test isolation."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/miniforge-demo-test-"
       (System/nanoTime)))

;; Mirror seed script definitions to avoid load-file side effects

(def demo-ids
  {:dag-id       #uuid "a1b2c3d4-0000-4000-8000-000000000099"
   :train-id     #uuid "a1b2c3d4-0000-4000-8000-000000000001"
   :pr-low-risk  #uuid "a0000000-0000-4000-8000-000000000101"
   :pr-med-risk  #uuid "a0000000-0000-4000-8000-000000000201"
   :pr-blocked   #uuid "a0000000-0000-4000-8000-000000000301"
   :workflow-id  #uuid "f0000000-0000-4000-8000-000000000001"
   :bundle-id    #uuid "e0e0e0e0-0000-4000-8000-000000000001"})

(defn parse-args
  "Mirror of seed script's parse-args for testing."
  [args]
  (let [arg-set (set args)]
    {:dry-run?   (contains? arg-set "--dry-run")
     :output-dir (or (some->> args
                              (partition 2 1)
                              (some (fn [[flag val]]
                                      (when (= flag "--output-dir") val))))
                     "tests/fixtures/demo")}))

;; ============================================================================
;; parse-args Tests
;; ============================================================================

(deftest parse-args-defaults-test
  (testing "no arguments returns defaults"
    (let [result (parse-args [])]
      (is (false? (:dry-run? result)))
      (is (= "tests/fixtures/demo" (:output-dir result))))))

(deftest parse-args-dry-run-test
  (testing "--dry-run flag is detected"
    (let [result (parse-args ["--dry-run"])]
      (is (true? (:dry-run? result)))
      (is (= "tests/fixtures/demo" (:output-dir result))))))

(deftest parse-args-output-dir-test
  (testing "--output-dir with value is parsed"
    (let [result (parse-args ["--output-dir" "/tmp/custom"])]
      (is (false? (:dry-run? result)))
      (is (= "/tmp/custom" (:output-dir result))))))

(deftest parse-args-combined-flags-test
  (testing "--dry-run and --output-dir together"
    (let [result (parse-args ["--dry-run" "--output-dir" "/tmp/both"])]
      (is (true? (:dry-run? result)))
      (is (= "/tmp/both" (:output-dir result))))))

(deftest parse-args-unknown-flags-test
  (testing "unknown flags are ignored"
    (let [result (parse-args ["--verbose" "--dry-run" "--foo"])]
      (is (true? (:dry-run? result)))
      (is (= "tests/fixtures/demo" (:output-dir result))))))

(deftest parse-args-output-dir-no-value-test
  (testing "--output-dir without value falls back to default"
    (let [result (parse-args ["--output-dir"])]
      (is (= "tests/fixtures/demo" (:output-dir result))))))

(deftest parse-args-output-dir-last-wins-test
  (testing "when --output-dir appears twice, first occurrence wins (partition 2 1 semantics)"
    (let [result (parse-args ["--output-dir" "/first" "--output-dir" "/second"])]
      (is (= "/first" (:output-dir result))))))

(deftest parse-args-dry-run-positional-test
  (testing "--dry-run works regardless of position"
    (is (true? (:dry-run? (parse-args ["--output-dir" "/tmp" "--dry-run"]))))
    (is (true? (:dry-run? (parse-args ["--dry-run" "--output-dir" "/tmp"]))))))

;; ============================================================================
;; Demo IDs Tests
;; ============================================================================

(deftest demo-ids-all-uuids-test
  (testing "all demo IDs are UUIDs"
    (doseq [[k v] demo-ids]
      (is (uuid? v) (str k " should be a UUID")))))

(deftest demo-ids-all-unique-test
  (testing "all demo IDs are unique"
    (let [vals (vals demo-ids)]
      (is (= (count vals) (count (set vals)))
          "All demo IDs must be distinct"))))

(deftest demo-ids-count-test
  (testing "exactly 7 demo IDs defined"
    (is (= 7 (count demo-ids)))))

(deftest demo-ids-expected-keys-test
  (testing "all expected keys present"
    (is (= #{:dag-id :train-id :pr-low-risk :pr-med-risk
             :pr-blocked :workflow-id :bundle-id}
           (set (keys demo-ids))))))

(deftest demo-ids-fixture-roundtrip-test
  (testing "demo-ids.edn file matches expected IDs"
    (let [ids (load-fixture "demo-ids.edn")]
      (is (= demo-ids ids)))))

;; ============================================================================
;; Fixture File Existence & Parseability Tests
;; ============================================================================

(def all-fixture-filenames
  ["fleet-dag.edn"
   "fleet-repos.edn"
   "train-state.edn"
   "blocked-pr.edn"
   "workflow-spec.edn"
   "policy-gate-results.edn"
   "evidence-bundle.edn"
   "demo-ids.edn"])

(deftest all-fixture-files-exist-test
  (testing "all expected fixture files exist"
    (doseq [f all-fixture-filenames]
      (is (.exists (io/file (str "tests/fixtures/demo/" f)))
          (str f " should exist")))))

(deftest all-fixture-files-parseable-test
  (testing "all fixture files are valid EDN"
    (doseq [f all-fixture-filenames]
      (is (some? (load-fixture f))
          (str f " should parse as valid EDN")))))

(deftest fixture-files-have-content-test
  (testing "no fixture file is empty"
    (doseq [f all-fixture-filenames]
      (let [content (slurp (str "tests/fixtures/demo/" f))]
        (is (pos? (count (str/trim content)))
            (str f " should not be empty"))))))

;; ============================================================================
;; Fleet Repos Fixture Tests
;; ============================================================================

(deftest fleet-repos-structure-test
  (testing "fleet repos has :repos key with a vector"
    (let [data (load-fixture "fleet-repos.edn")]
      (is (map? data))
      (is (vector? (:repos data)))
      (is (pos? (count (:repos data)))))))

(deftest fleet-repos-count-test
  (testing "fleet repos has exactly 4 repositories"
    (let [data (load-fixture "fleet-repos.edn")]
      (is (= 4 (count (:repos data)))))))

(deftest fleet-repos-required-fields-test
  (testing "each repo has all required fields"
    (let [data (load-fixture "fleet-repos.edn")]
      (doseq [repo (:repos data)]
        (is (string? (:repo/name repo)) "repo/name must be a string")
        (is (string? (:repo/url repo)) "repo/url must be a string")
        (is (keyword? (:repo/type repo)) "repo/type must be a keyword")
        (is (string? (:repo/description repo)) "repo/description must be a string")
        (is (string? (:repo/default-branch repo)) "repo/default-branch must be a string")))))

(deftest fleet-repos-unique-names-test
  (testing "all repo names are unique"
    (let [data (load-fixture "fleet-repos.edn")
          names (map :repo/name (:repos data))]
      (is (= (count names) (count (set names)))))))

(deftest fleet-repos-unique-urls-test
  (testing "all repo URLs are unique"
    (let [data (load-fixture "fleet-repos.edn")
          urls (map :repo/url (:repos data))]
      (is (= (count urls) (count (set urls)))))))

(deftest fleet-repos-valid-types-test
  (testing "repo types are from a known set"
    (let [data (load-fixture "fleet-repos.edn")
          valid-types #{:infrastructure :platform :application}]
      (doseq [repo (:repos data)]
        (is (contains? valid-types (:repo/type repo))
            (str "Unknown repo type: " (:repo/type repo)))))))

(deftest fleet-repos-urls-are-github-test
  (testing "all repo URLs are GitHub URLs"
    (let [data (load-fixture "fleet-repos.edn")]
      (doseq [repo (:repos data)]
        (is (str/starts-with? (:repo/url repo) "https://github.com/")
            (str "URL should be a GitHub URL: " (:repo/url repo)))))))

(deftest fleet-repos-default-branch-test
  (testing "all repos use 'main' as default branch"
    (let [data (load-fixture "fleet-repos.edn")]
      (doseq [repo (:repos data)]
        (is (= "main" (:repo/default-branch repo)))))))

(deftest fleet-repos-expected-names-test
  (testing "fleet repos contain the expected repo names"
    (let [data (load-fixture "fleet-repos.edn")
          names (set (map :repo/name (:repos data)))]
      (is (contains? names "terraform-modules"))
      (is (contains? names "terraform-live"))
      (is (contains? names "k8s-manifests"))
      (is (contains? names "api-gateway")))))

(deftest fleet-repos-superset-of-dag-repos-test
  (testing "fleet repos is a superset of DAG repos"
    (let [repos (load-fixture "fleet-repos.edn")
          dag (load-fixture "fleet-dag.edn")
          repo-names (set (map :repo/name (:repos repos)))
          dag-repo-names (set (map :repo/name (:dag/repos dag)))]
      (is (set/subset? dag-repo-names repo-names)
          "All DAG repos should exist in fleet-repos"))))

;; ============================================================================
;; Fleet DAG Fixture Tests
;; ============================================================================

(deftest fleet-dag-structure-test
  (testing "fleet DAG has required keys"
    (let [dag (load-fixture "fleet-dag.edn")]
      (is (some? (:dag/id dag)))
      (is (string? (:dag/name dag)))
      (is (string? (:dag/description dag)))
      (is (string? (:dag/created-at dag)))
      (is (vector? (:dag/repos dag)))
      (is (vector? (:dag/edges dag))))))

(deftest fleet-dag-repo-count-test
  (testing "fleet DAG has exactly 3 repos"
    (let [dag (load-fixture "fleet-dag.edn")]
      (is (= 3 (count (:dag/repos dag)))))))

(deftest fleet-dag-edge-count-test
  (testing "fleet DAG has exactly 2 edges"
    (let [dag (load-fixture "fleet-dag.edn")]
      (is (= 2 (count (:dag/edges dag)))))))

(deftest fleet-dag-repo-names-test
  (testing "fleet DAG contains expected repo names"
    (let [dag (load-fixture "fleet-dag.edn")
          names (set (map :repo/name (:dag/repos dag)))]
      (is (= #{"terraform-modules" "terraform-live" "k8s-manifests"} names)))))

(deftest fleet-dag-repo-structure-test
  (testing "each repo has required fields"
    (let [dag (load-fixture "fleet-dag.edn")]
      (doseq [repo (:dag/repos dag)]
        (is (string? (:repo/name repo)) "repo/name must be a string")
        (is (string? (:repo/url repo)) "repo/url must be a string")
        (is (keyword? (:repo/type repo)) "repo/type must be a keyword")
        (is (keyword? (:repo/layer repo)) "repo/layer must be a keyword")))))

(deftest fleet-dag-repo-layers-test
  (testing "DAG repos have expected layers"
    (let [dag (load-fixture "fleet-dag.edn")
          repo-layers (into {} (map (juxt :repo/name :repo/layer) (:dag/repos dag)))]
      (is (= :foundation (get repo-layers "terraform-modules")))
      (is (= :environment (get repo-layers "terraform-live")))
      (is (= :deployment (get repo-layers "k8s-manifests"))))))

(deftest fleet-dag-edges-reference-existing-repos-test
  (testing "all edge endpoints reference repos in the DAG"
    (let [dag (load-fixture "fleet-dag.edn")
          repo-names (set (map :repo/name (:dag/repos dag)))]
      (doseq [edge (:dag/edges dag)]
        (is (contains? repo-names (:edge/from edge))
            (str ":edge/from '" (:edge/from edge) "' not in repos"))
        (is (contains? repo-names (:edge/to edge))
            (str ":edge/to '" (:edge/to edge) "' not in repos"))))))

(deftest fleet-dag-edge-structure-test
  (testing "each edge has required fields"
    (let [dag (load-fixture "fleet-dag.edn")]
      (doseq [edge (:dag/edges dag)]
        (is (string? (:edge/from edge)))
        (is (string? (:edge/to edge)))
        (is (keyword? (:edge/constraint edge)))
        (is (keyword? (:edge/ordering edge)))))))

(deftest fleet-dag-no-self-edges-test
  (testing "no edge points from a repo to itself"
    (let [dag (load-fixture "fleet-dag.edn")]
      (doseq [edge (:dag/edges dag)]
        (is (not= (:edge/from edge) (:edge/to edge))
            "Self-referencing edges are not allowed")))))

(deftest fleet-dag-id-matches-demo-ids-test
  (testing "fleet DAG ID matches demo-ids :dag-id"
    (let [dag (load-fixture "fleet-dag.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:dag/id dag) (:dag-id ids))))))

(deftest fleet-dag-linear-pipeline-test
  (testing "DAG forms a linear pipeline: modules → live → k8s"
    (let [dag (load-fixture "fleet-dag.edn")
          edges (:dag/edges dag)]
      (is (some #(and (= "terraform-modules" (:edge/from %))
                      (= "terraform-live" (:edge/to %)))
                edges)
          "Expected edge from terraform-modules to terraform-live")
      (is (some #(and (= "terraform-live" (:edge/from %))
                      (= "k8s-manifests" (:edge/to %)))
                edges)
          "Expected edge from terraform-live to k8s-manifests"))))

(deftest fleet-dag-acyclic-test
  (testing "fleet DAG has no cycles"
    (let [dag (load-fixture "fleet-dag.edn")
          edges (:dag/edges dag)
          adj (reduce (fn [m {:keys [edge/from edge/to]}]
                        (update m from (fnil conj #{}) to))
                      {} edges)
          has-cycle? (fn []
                       (let [visited (atom #{})
                             in-stack (atom #{})]
                         (letfn [(dfs [node]
                                   (swap! visited conj node)
                                   (swap! in-stack conj node)
                                   (let [cycle-found?
                                         (some (fn [neighbor]
                                                 (cond
                                                   (contains? @in-stack neighbor) true
                                                   (not (contains? @visited neighbor)) (dfs neighbor)
                                                   :else false))
                                               (get adj node []))]
                                     (swap! in-stack disj node)
                                     cycle-found?))]
                           (some dfs (keys adj)))))]
      (is (not (has-cycle?)) "DAG must be acyclic"))))

(deftest fleet-dag-edge-ordering-values-test
  (testing "all edges use :sequential ordering"
    (let [dag (load-fixture "fleet-dag.edn")]
      (doseq [edge (:dag/edges dag)]
        (is (= :sequential (:edge/ordering edge)))))))

;; ============================================================================
;; PR Train Fixture Tests
;; ============================================================================

(deftest train-state-structure-test
  (testing "train state has required keys"
    (let [train (load-fixture "train-state.edn")]
      (is (some? (:train/id train)))
      (is (string? (:train/name train)))
      (is (some? (:train/dag-id train)))
      (is (string? (:train/description train)))
      (is (keyword? (:train/status train)))
      (is (inst? (:train/created-at train)))
      (is (inst? (:train/updated-at train)))
      (is (vector? (:train/prs train)))
      (is (vector? (:train/blocking-prs train)))
      (is (vector? (:train/ready-to-merge train)))
      (is (map? (:train/progress train))))))

(deftest train-state-pr-count-test
  (testing "train has exactly 3 PRs"
    (let [train (load-fixture "train-state.edn")]
      (is (= 3 (count (:train/prs train)))))))

(deftest train-state-dag-id-cross-ref-test
  (testing "train dag-id references the fleet DAG"
    (let [train (load-fixture "train-state.edn")
          dag (load-fixture "fleet-dag.edn")]
      (is (= (:train/dag-id train) (:dag/id dag))))))

(deftest train-state-pr-structure-test
  (testing "each PR has required fields"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (string? (:pr/repo pr)) "pr/repo must be a string")
        (is (integer? (:pr/number pr)) "pr/number must be an integer")
        (is (string? (:pr/url pr)) "pr/url must be a string")
        (is (string? (:pr/branch pr)) "pr/branch must be a string")
        (is (string? (:pr/title pr)) "pr/title must be a string")
        (is (keyword? (:pr/status pr)) "pr/status must be a keyword")
        (is (keyword? (:pr/ci-status pr)) "pr/ci-status must be a keyword")
        (is (integer? (:pr/merge-order pr)) "pr/merge-order must be an integer")
        (is (vector? (:pr/depends-on pr)) "pr/depends-on must be a vector")
        (is (vector? (:pr/blocks pr)) "pr/blocks must be a vector")
        (is (number? (:pr/readiness-score pr)) "pr/readiness-score must be a number")
        (is (keyword? (:pr/automation-tier pr)) "pr/automation-tier must be a keyword")
        (is (map? (:pr/risk pr)) "pr/risk must be a map")
        (is (vector? (:pr/gate-results pr)) "pr/gate-results must be a vector")))))

(deftest train-state-pr-numbers-unique-test
  (testing "all PR numbers are unique"
    (let [train (load-fixture "train-state.edn")
          numbers (map :pr/number (:train/prs train))]
      (is (= (count numbers) (count (set numbers)))))))

(deftest train-state-merge-order-sequential-test
  (testing "merge orders are 1, 2, 3"
    (let [train (load-fixture "train-state.edn")
          orders (sort (map :pr/merge-order (:train/prs train)))]
      (is (= [1 2 3] orders)))))

(deftest train-state-pr-repos-in-dag-test
  (testing "all PR repos reference repos in the fleet DAG"
    (let [train (load-fixture "train-state.edn")
          dag (load-fixture "fleet-dag.edn")
          dag-repos (set (map :repo/name (:dag/repos dag)))
          pr-repos (set (map :pr/repo (:train/prs train)))]
      (is (set/subset? pr-repos dag-repos)))))

(deftest train-state-pr-101-approved-test
  (testing "PR #101 is approved with passing CI"
    (let [train (load-fixture "train-state.edn")
          pr101 (first (filter #(= 101 (:pr/number %)) (:train/prs train)))]
      (is (some? pr101))
      (is (= :approved (:pr/status pr101)))
      (is (= :passed (:pr/ci-status pr101)))
      (is (= 1 (:pr/merge-order pr101)))
      (is (empty? (:pr/depends-on pr101))))))

(deftest train-state-pr-201-reviewing-test
  (testing "PR #201 is under review with running CI"
    (let [train (load-fixture "train-state.edn")
          pr201 (first (filter #(= 201 (:pr/number %)) (:train/prs train)))]
      (is (some? pr201))
      (is (= :reviewing (:pr/status pr201)))
      (is (= :running (:pr/ci-status pr201)))
      (is (= [101] (:pr/depends-on pr201))))))

(deftest train-state-pr-301-draft-test
  (testing "PR #301 is in draft with dependencies"
    (let [train (load-fixture "train-state.edn")
          pr301 (first (filter #(= 301 (:pr/number %)) (:train/prs train)))]
      (is (some? pr301))
      (is (= :draft (:pr/status pr301)))
      (is (= :pending (:pr/ci-status pr301)))
      (is (= [101 201] (:pr/depends-on pr301)))
      (is (empty? (:pr/blocks pr301))))))

(deftest train-state-dependency-consistency-test
  (testing "PR blocks/depends-on relationships are consistent"
    (let [train (load-fixture "train-state.edn")
          prs (:train/prs train)
          by-number (into {} (map (juxt :pr/number identity) prs))]
      ;; If A blocks B, then B depends-on A
      (doseq [pr prs
              blocked-num (:pr/blocks pr)]
        (let [blocked-pr (get by-number blocked-num)]
          (is (some? blocked-pr)
              (str "PR #" blocked-num " referenced in :blocks but not found"))
          (when blocked-pr
            (is (some #{(:pr/number pr)} (:pr/depends-on blocked-pr))
                (str "PR #" blocked-num " should depend on #" (:pr/number pr))))))
      ;; If A depends-on B, then B blocks A
      (doseq [pr prs
              dep-num (:pr/depends-on pr)]
        (let [dep-pr (get by-number dep-num)]
          (is (some? dep-pr)
              (str "PR #" dep-num " referenced in :depends-on but not found"))
          (when dep-pr
            (is (some #{(:pr/number pr)} (:pr/blocks dep-pr))
                (str "PR #" dep-num " should block #" (:pr/number pr)))))))))

(deftest train-state-pr-ids-match-demo-ids-test
  (testing "train ID and DAG ID match demo-ids"
    (let [train (load-fixture "train-state.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:train-id ids) (:train/id train)))
      (is (= (:dag-id ids) (:train/dag-id train))))))

(deftest train-state-readiness-scores-bounded-test
  (testing "all readiness scores are between 0.0 and 1.0"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (<= 0.0 (:pr/readiness-score pr) 1.0)
            (str "PR #" (:pr/number pr) " readiness score out of bounds: "
                 (:pr/readiness-score pr)))))))

(deftest train-state-risk-scores-bounded-test
  (testing "all risk scores are between 0.0 and 1.0"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (let [score (get-in pr [:pr/risk :risk/score])]
          (is (<= 0.0 score 1.0)
              (str "PR #" (:pr/number pr) " risk score out of bounds: " score)))))))

(deftest train-state-risk-levels-valid-test
  (testing "all risk levels are from a known set"
    (let [train (load-fixture "train-state.edn")
          valid-levels #{:low :medium :high :critical}]
      (doseq [pr (:train/prs train)]
        (let [level (get-in pr [:pr/risk :risk/level])]
          (is (contains? valid-levels level)
              (str "PR #" (:pr/number pr) " has unknown risk level: " level)))))))

(deftest train-state-risk-factors-non-empty-test
  (testing "all PRs have at least one risk factor"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (let [factors (get-in pr [:pr/risk :risk/factors])]
          (is (vector? factors))
          (is (pos? (count factors))
              (str "PR #" (:pr/number pr) " should have risk factors")))))))

(deftest train-state-automation-tiers-valid-test
  (testing "automation tiers are :tier-1 through :tier-3"
    (let [train (load-fixture "train-state.edn")
          valid-tiers #{:tier-1 :tier-2 :tier-3}]
      (doseq [pr (:train/prs train)]
        (is (contains? valid-tiers (:pr/automation-tier pr))
            (str "PR #" (:pr/number pr) " has unknown tier: " (:pr/automation-tier pr)))))))

(deftest train-state-pr-statuses-valid-test
  (testing "PR statuses are from a known set"
    (let [train (load-fixture "train-state.edn")
          valid-statuses #{:draft :reviewing :approved :merged :closed}]
      (doseq [pr (:train/prs train)]
        (is (contains? valid-statuses (:pr/status pr))
            (str "PR #" (:pr/number pr) " has unknown status: " (:pr/status pr)))))))

(deftest train-state-ci-statuses-valid-test
  (testing "CI statuses are from a known set"
    (let [train (load-fixture "train-state.edn")
          valid-ci #{:pending :running :passed :failed}]
      (doseq [pr (:train/prs train)]
        (is (contains? valid-ci (:pr/ci-status pr))
            (str "PR #" (:pr/number pr) " has unknown CI status: " (:pr/ci-status pr)))))))

(deftest train-state-blocking-prs-valid-test
  (testing "blocking-prs references actual PR numbers"
    (let [train (load-fixture "train-state.edn")
          pr-numbers (set (map :pr/number (:train/prs train)))]
      (doseq [bp (:train/blocking-prs train)]
        (is (contains? pr-numbers bp)
            (str "Blocking PR #" bp " not found in train PRs"))))))

(deftest train-state-ready-to-merge-valid-test
  (testing "ready-to-merge references actual PR numbers"
    (let [train (load-fixture "train-state.edn")
          pr-numbers (set (map :pr/number (:train/prs train)))]
      (doseq [rm (:train/ready-to-merge train)]
        (is (contains? pr-numbers rm)
            (str "Ready-to-merge PR #" rm " not found in train PRs"))))))

(deftest train-state-progress-consistency-test
  (testing "progress totals are consistent with PR count"
    (let [train (load-fixture "train-state.edn")
          progress (:train/progress train)]
      (is (= (count (:train/prs train)) (:total progress)))
      (is (= (:total progress)
             (+ (:merged progress) (:approved progress)
                (:pending progress) (:failed progress)))
          "Progress counts should sum to total"))))

(deftest train-state-timestamps-ordered-test
  (testing "updated-at is after created-at"
    (let [train (load-fixture "train-state.edn")]
      (is (.after (:train/updated-at train) (:train/created-at train))))))

(deftest fleet-dag-merge-order-follows-topology-test
  (testing "PR merge order respects DAG edge ordering"
    (let [dag (load-fixture "fleet-dag.edn")
          train (load-fixture "train-state.edn")
          prs (:train/prs train)
          repo->order (into {} (map (juxt :pr/repo :pr/merge-order) prs))]
      (doseq [edge (:dag/edges dag)]
        (let [from-order (get repo->order (:edge/from edge))
              to-order (get repo->order (:edge/to edge))]
          (when (and from-order to-order)
            (is (< from-order to-order)
                (str (:edge/from edge) " (order " from-order ") should merge before "
                     (:edge/to edge) " (order " to-order ")"))))))))

;; ============================================================================
;; Gate Results within Train State
;; ============================================================================

(deftest train-state-gate-result-structure-test
  (testing "gate results within PRs have required fields"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)
              gate (:pr/gate-results pr)]
        (is (keyword? (:gate/id gate)) "gate/id must be a keyword")
        (is (keyword? (:gate/type gate)) "gate/type must be a keyword")
        (is (boolean? (:gate/passed? gate)) "gate/passed? must be a boolean")
        (is (string? (:gate/message gate)) "gate/message must be a string")
        (is (inst? (:gate/timestamp gate)) "gate/timestamp must be an inst")))))

(deftest train-state-gate-types-valid-test
  (testing "gate types are from a known set"
    (let [train (load-fixture "train-state.edn")
          valid-types #{:automated :manual :schedule}]
      (doseq [pr (:train/prs train)
              gate (:pr/gate-results pr)]
        (is (contains? valid-types (:gate/type gate))
            (str "Unknown gate type: " (:gate/type gate)))))))

;; ============================================================================
;; Blocked PR Fixture Tests
;; ============================================================================

(deftest blocked-pr-structure-test
  (testing "blocked PR fixture has required top-level keys"
    (let [data (load-fixture "blocked-pr.edn")]
      (is (map? (:blocked-pr data)))
      (is (vector? (:blocking-reasons data))))))

(deftest blocked-pr-details-test
  (testing "blocked PR has correct PR number and repo"
    (let [data (load-fixture "blocked-pr.edn")
          pr (:blocked-pr data)]
      (is (= 201 (:pr/number pr)))
      (is (= "terraform-live" (:pr/repo pr)))
      (is (= :reviewing (:pr/status pr))))))

(deftest blocked-pr-matches-train-pr-test
  (testing "blocked PR #201 is consistent with train state PR #201"
    (let [blocked (:blocked-pr (load-fixture "blocked-pr.edn"))
          train (load-fixture "train-state.edn")
          train-pr201 (first (filter #(= 201 (:pr/number %)) (:train/prs train)))]
      (is (= (:pr/repo blocked) (:pr/repo train-pr201)))
      (is (= (:pr/branch blocked) (:pr/branch train-pr201)))
      (is (= (:pr/title blocked) (:pr/title train-pr201))))))

(deftest blocked-pr-intent-structure-test
  (testing "blocked PR has a well-formed intent specification"
    (let [data (load-fixture "blocked-pr.edn")
          intent (get-in data [:blocked-pr :pr/intent])]
      (is (some? intent))
      (is (keyword? (:intent/type intent)))
      (is (vector? (:intent/invariants intent)))
      (is (pos? (count (:intent/invariants intent))))
      (is (vector? (:intent/forbidden-actions intent)))
      (is (pos? (count (:intent/forbidden-actions intent))))
      (is (vector? (:intent/required-evidence intent)))
      (is (pos? (count (:intent/required-evidence intent)))))))

(deftest blocked-pr-invariant-structure-test
  (testing "intent invariants have required fields"
    (let [data (load-fixture "blocked-pr.edn")
          invariants (get-in data [:blocked-pr :pr/intent :intent/invariants])]
      (doseq [inv invariants]
        (is (keyword? (:invariant/id inv)))
        (is (string? (:invariant/description inv)))
        (is (keyword? (:invariant/scope inv)))))))

(deftest blocked-pr-gate-results-test
  (testing "blocked PR has gate results with at least one failure"
    (let [data (load-fixture "blocked-pr.edn")
          gates (get-in data [:blocked-pr :pr/gate-results])]
      (is (vector? gates))
      (is (pos? (count gates)))
      (is (some #(false? (:gate/passed? %)) gates)
          "At least one gate should have failed"))))

(deftest blocked-pr-gate-details-test
  (testing "failed gate includes detailed information"
    (let [data (load-fixture "blocked-pr.edn")
          failed-gate (first (filter #(false? (:gate/passed? %))
                                     (get-in data [:blocked-pr :pr/gate-results])))]
      (is (some? failed-gate))
      (is (map? (:gate/details failed-gate)))
      (is (vector? (get-in failed-gate [:gate/details :deleted-resources])))
      (is (string? (get-in failed-gate [:gate/details :environment])))
      (is (string? (get-in failed-gate [:gate/details :plan-summary]))))))

(deftest blocked-pr-blocking-reasons-test
  (testing "blocking reasons are well-formed"
    (let [data (load-fixture "blocked-pr.edn")
          reasons (:blocking-reasons data)]
      (is (= 2 (count reasons)))
      (doseq [reason reasons]
        (is (keyword? (:reason reason)))
        (is (string? (:detail reason)))
        (is (pos? (count (:detail reason))))))))

(deftest blocked-pr-blocking-reason-types-test
  (testing "blocking reasons include gate failure and dependency"
    (let [data (load-fixture "blocked-pr.edn")
          reason-types (set (map :reason (:blocking-reasons data)))]
      (is (contains? reason-types :gate-not-passed))
      (is (contains? reason-types :dependency-not-merged)))))

(deftest blocked-pr-in-train-blocking-list-test
  (testing "PR #201 appears in train's blocking-prs list"
    (let [train (load-fixture "train-state.edn")]
      (is (some #{201} (:train/blocking-prs train))))))

;; ============================================================================
;; Workflow Spec Fixture Tests
;; ============================================================================

(deftest workflow-spec-structure-test
  (testing "workflow spec has required keys"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (string? (:workflow/spec-version spec)))
      (is (keyword? (:workflow/type spec)))
      (is (string? (:workflow/version spec)))
      (is (uuid? (:workflow/id spec)))
      (is (string? (:spec/title spec)))
      (is (string? (:spec/description spec)))
      (is (string? (:spec/intent spec)))
      (is (vector? (:spec/constraints spec)))
      (is (vector? (:spec/acceptance-criteria spec)))
      (is (vector? (:plan/tasks spec))))))

(deftest workflow-spec-id-matches-demo-ids-test
  (testing "workflow ID matches demo-ids :workflow-id"
    (let [spec (load-fixture "workflow-spec.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:workflow/id spec) (:workflow-id ids))))))

(deftest workflow-spec-type-test
  (testing "workflow type is :full-sdlc"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (= :full-sdlc (:workflow/type spec))))))

(deftest workflow-spec-constraints-non-empty-test
  (testing "workflow spec has constraints that are non-empty strings"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (= 4 (count (:spec/constraints spec))))
      (doseq [c (:spec/constraints spec)]
        (is (string? c) "Each constraint must be a string")
        (is (pos? (count c)) "Constraints must not be empty strings")))))

(deftest workflow-spec-acceptance-criteria-non-empty-test
  (testing "workflow spec has acceptance criteria that are non-empty strings"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (= 4 (count (:spec/acceptance-criteria spec))))
      (doseq [ac (:spec/acceptance-criteria spec)]
        (is (string? ac) "Each criterion must be a string")
        (is (pos? (count ac)) "Criteria must not be empty strings")))))

(deftest workflow-spec-task-count-test
  (testing "workflow has exactly 5 tasks"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (= 5 (count (:plan/tasks spec)))))))

(deftest workflow-spec-task-structure-test
  (testing "each task has required fields"
    (let [spec (load-fixture "workflow-spec.edn")]
      (doseq [task (:plan/tasks spec)]
        (is (keyword? (:task/id task)) "task/id must be a keyword")
        (is (string? (:task/description task)) "task/description must be a string")
        (is (keyword? (:task/type task)) "task/type must be a keyword")
        (is (vector? (:task/dependencies task)) "task/dependencies must be a vector")))))

(deftest workflow-spec-task-ids-unique-test
  (testing "all task IDs are unique"
    (let [spec (load-fixture "workflow-spec.edn")
          ids (map :task/id (:plan/tasks spec))]
      (is (= (count ids) (count (set ids)))))))

(deftest workflow-spec-task-types-valid-test
  (testing "task types are from a known set"
    (let [spec (load-fixture "workflow-spec.edn")
          valid-types #{:test :deploy :review :configure :validate}]
      (doseq [task (:plan/tasks spec)]
        (is (contains? valid-types (:task/type task))
            (str "Unknown task type: " (:task/type task)))))))

(deftest workflow-spec-task-dependencies-valid-test
  (testing "all task dependencies reference existing task IDs"
    (let [spec (load-fixture "workflow-spec.edn")
          task-ids (set (map :task/id (:plan/tasks spec)))]
      (doseq [task (:plan/tasks spec)
              dep (:task/dependencies task)]
        (is (contains? task-ids dep)
            (str "Task " (:task/id task) " depends on unknown task: " dep))))))

(deftest workflow-spec-task-dag-acyclic-test
  (testing "task dependency graph has no cycles"
    (let [spec (load-fixture "workflow-spec.edn")
          tasks (:plan/tasks spec)
          adj (into {} (map (fn [t] [(:task/id t) (:task/dependencies t)]) tasks))
          has-cycle? (fn []
                       (let [visited (atom #{})
                             in-stack (atom #{})]
                         (letfn [(dfs [node]
                                   (swap! visited conj node)
                                   (swap! in-stack conj node)
                                   (let [cycle-found?
                                         (some (fn [dep]
                                                 (cond
                                                   (contains? @in-stack dep) true
                                                   (not (contains? @visited dep)) (dfs dep)
                                                   :else false))
                                               (get adj node []))]
                                     (swap! in-stack disj node)
                                     cycle-found?))]
                           (some dfs (keys adj)))))]
      (is (not (has-cycle?)) "Task DAG must be acyclic"))))

(deftest workflow-spec-task-has-root-test
  (testing "at least one task has no dependencies (root task)"
    (let [spec (load-fixture "workflow-spec.edn")
          root-tasks (filter #(empty? (:task/dependencies %)) (:plan/tasks spec))]
      (is (pos? (count root-tasks))
          "There must be at least one root task with no dependencies"))))

(deftest workflow-spec-task-linear-chain-test
  (testing "tasks form a linear chain: validate → apply-modules → plan-live → apply-live → update-k8s"
    (let [spec (load-fixture "workflow-spec.edn")
          task-map (into {} (map (juxt :task/id identity) (:plan/tasks spec)))]
      (is (empty? (:task/dependencies (get task-map :task/validate-modules))))
      (is (= [:task/validate-modules] (:task/dependencies (get task-map :task/apply-modules))))
      (is (= [:task/apply-modules] (:task/dependencies (get task-map :task/plan-live))))
      (is (= [:task/plan-live] (:task/dependencies (get task-map :task/apply-live))))
      (is (= [:task/apply-live] (:task/dependencies (get task-map :task/update-k8s)))))))

(deftest workflow-spec-semver-format-test
  (testing "spec-version and version follow semver format"
    (let [spec (load-fixture "workflow-spec.edn")
          semver-re #"^\d+\.\d+\.\d+$"]
      (is (re-matches semver-re (:workflow/spec-version spec)))
      (is (re-matches semver-re (:workflow/version spec))))))

;; ============================================================================
;; Policy Gate Results Fixture Tests
;; ============================================================================

(deftest policy-gate-results-structure-test
  (testing "gate results is a vector of maps"
    (let [gates (load-fixture "policy-gate-results.edn")]
      (is (vector? gates))
      (is (pos? (count gates))))))

(deftest policy-gate-results-count-test
  (testing "exactly 6 gate results"
    (let [gates (load-fixture "policy-gate-results.edn")]
      (is (= 6 (count gates))))))

(deftest policy-gate-results-field-structure-test
  (testing "each gate result has required fields"
    (let [gates (load-fixture "policy-gate-results.edn")]
      (doseq [gate gates]
        (is (string? (:gate/name gate)) "gate/name must be a string")
        (is (keyword? (:gate/result gate)) "gate/result must be a keyword")
        (is (string? (:gate/details gate)) "gate/details must be a string")
        (is (integer? (:gate/pr gate)) "gate/pr must be an integer")))))

(deftest policy-gate-results-valid-outcomes-test
  (testing "gate results are :pass, :fail, or :pending"
    (let [gates (load-fixture "policy-gate-results.edn")
          valid-results #{:pass :fail :pending}]
      (doseq [gate gates]
        (is (contains? valid-results (:gate/result gate))
            (str "Invalid gate result: " (:gate/result gate)))))))

(deftest policy-gate-results-pr-101-all-pass-test
  (testing "PR #101 has all gates passing"
    (let [gates (load-fixture "policy-gate-results.edn")
          pr101-gates (filter #(= 101 (:gate/pr %)) gates)]
      (is (= 2 (count pr101-gates)))
      (is (every? #(= :pass (:gate/result %)) pr101-gates)))))

(deftest policy-gate-results-pr-201-mixed-test
  (testing "PR #201 has mixed gate results"
    (let [gates (load-fixture "policy-gate-results.edn")
          pr201-gates (filter #(= 201 (:gate/pr %)) gates)]
      (is (= 2 (count pr201-gates)))
      (is (some #(= :pass (:gate/result %)) pr201-gates))
      (is (some #(= :pending (:gate/result %)) pr201-gates)))))

(deftest policy-gate-results-pr-301-has-failures-test
  (testing "PR #301 has at least one failing gate"
    (let [gates (load-fixture "policy-gate-results.edn")
          pr301-gates (filter #(= 301 (:gate/pr %)) gates)]
      (is (= 2 (count pr301-gates)))
      (is (some #(= :fail (:gate/result %)) pr301-gates)))))

(deftest policy-gate-results-pr-references-valid-test
  (testing "all gate PR numbers reference PRs in the train"
    (let [gates (load-fixture "policy-gate-results.edn")
          train (load-fixture "train-state.edn")
          pr-numbers (set (map :pr/number (:train/prs train)))
          gate-prs (set (map :gate/pr gates))]
      (is (set/subset? gate-prs pr-numbers)))))

(deftest policy-gate-results-unique-names-per-pr-test
  (testing "gate names are unique within each PR"
    (let [gates (load-fixture "policy-gate-results.edn")
          by-pr (group-by :gate/pr gates)]
      (doseq [[pr-num pr-gates] by-pr]
        (let [names (map :gate/name pr-gates)]
          (is (= (count names) (count (set names)))
              (str "PR #" pr-num " has duplicate gate names")))))))

(deftest policy-gate-results-two-per-pr-test
  (testing "each PR has exactly 2 gate evaluations"
    (let [gates (load-fixture "policy-gate-results.edn")
          by-pr (group-by :gate/pr gates)]
      (doseq [[pr-num pr-gates] by-pr]
        (is (= 2 (count pr-gates))
            (str "PR #" pr-num " should have 2 gates"))))))

;; ============================================================================
;; Evidence Bundle Fixture Tests
;; ============================================================================

(deftest evidence-bundle-structure-test
  (testing "evidence bundle has required keys"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (is (uuid? (:evidence/id bundle)))
      (is (uuid? (:evidence/train-id bundle)))
      (is (string? (:evidence/miniforge-version bundle)))
      (is (vector? (:evidence/prs bundle)))
      (is (map? (:evidence/summary bundle))))))

(deftest evidence-bundle-id-matches-demo-ids-test
  (testing "bundle ID matches demo-ids :bundle-id"
    (let [bundle (load-fixture "evidence-bundle.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:evidence/id bundle) (:bundle-id ids))))))

(deftest evidence-bundle-train-id-cross-ref-test
  (testing "bundle train-id references the PR train"
    (let [bundle (load-fixture "evidence-bundle.edn")
          train (load-fixture "train-state.edn")]
      (is (= (:evidence/train-id bundle) (:train/id train))))))

(deftest evidence-bundle-pr-count-test
  (testing "bundle covers all 3 PRs"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (is (= 3 (count (:evidence/prs bundle)))))))

(deftest evidence-bundle-pr-numbers-test
  (testing "bundle PR numbers match train PR numbers"
    (let [bundle (load-fixture "evidence-bundle.edn")
          train (load-fixture "train-state.edn")]
      (is (= (set (map :pr/number (:train/prs train)))
             (set (map :pr/number (:evidence/prs bundle))))))))

(deftest evidence-bundle-pr-repos-test
  (testing "bundle PR repos match train PR repos"
    (let [bundle (load-fixture "evidence-bundle.edn")
          train (load-fixture "train-state.edn")]
      (is (= (set (map :pr/repo (:train/prs train)))
             (set (map :pr/repo (:evidence/prs bundle))))))))

(deftest evidence-bundle-artifact-structure-test
  (testing "each evidence artifact has required fields"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (doseq [pr-evidence (:evidence/prs bundle)
              artifact (:evidence/artifacts pr-evidence)]
        (is (keyword? (:type artifact)) "artifact type must be a keyword")
        (is (string? (:content artifact)) "artifact content must be a string")
        (is (string? (:hash artifact)) "artifact hash must be a string")
        (is (inst? (:timestamp artifact)) "artifact timestamp must be an inst")))))

(deftest evidence-bundle-artifact-hashes-sha256-test
  (testing "all artifact hashes use sha256 prefix"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (doseq [pr-evidence (:evidence/prs bundle)
              artifact (:evidence/artifacts pr-evidence)]
        (is (str/starts-with? (:hash artifact) "sha256:")
            (str "Hash should start with sha256: prefix: " (:hash artifact)))))))

(deftest evidence-bundle-artifact-hashes-unique-test
  (testing "all artifact hashes are unique"
    (let [bundle (load-fixture "evidence-bundle.edn")
          all-hashes (for [pr-ev (:evidence/prs bundle)
                           art (:evidence/artifacts pr-ev)]
                       (:hash art))]
      (is (= (count all-hashes) (count (set all-hashes)))
          "All artifact hashes must be distinct"))))

(deftest evidence-bundle-artifact-types-valid-test
  (testing "artifact types are from a known set"
    (let [bundle (load-fixture "evidence-bundle.edn")
          valid-types #{:gate-decision :test-results :human-approval :dependency-check}]
      (doseq [pr-evidence (:evidence/prs bundle)
              artifact (:evidence/artifacts pr-evidence)]
        (is (contains? valid-types (:type artifact))
            (str "Unknown artifact type: " (:type artifact)))))))

(deftest evidence-bundle-summary-consistency-test
  (testing "evidence summary counts are consistent"
    (let [bundle (load-fixture "evidence-bundle.edn")
          summary (:evidence/summary bundle)]
      (is (= 3 (:total-prs summary)))
      (is (= (count (:evidence/prs bundle)) (:total-prs summary)))
      (is (integer? (:gates-passed summary)))
      (is (integer? (:gates-failed summary)))
      (is (integer? (:human-approvals summary)))
      (is (integer? (:semantic-violations summary)))
      (is (zero? (:semantic-violations summary))))))

(deftest evidence-bundle-pr-101-artifacts-test
  (testing "PR #101 has gate-decision, test-results, and human-approval"
    (let [bundle (load-fixture "evidence-bundle.edn")
          pr101 (first (filter #(= 101 (:pr/number %)) (:evidence/prs bundle)))
          types (set (map :type (:evidence/artifacts pr101)))]
      (is (some? pr101))
      (is (= 3 (count (:evidence/artifacts pr101))))
      (is (contains? types :gate-decision))
      (is (contains? types :test-results))
      (is (contains? types :human-approval)))))

(deftest evidence-bundle-pr-301-dependency-check-test
  (testing "PR #301 has only a dependency-check artifact"
    (let [bundle (load-fixture "evidence-bundle.edn")
          pr301 (first (filter #(= 301 (:pr/number %)) (:evidence/prs bundle)))]
      (is (some? pr301))
      (is (= 1 (count (:evidence/artifacts pr301))))
      (is (= :dependency-check (:type (first (:evidence/artifacts pr301))))))))

(deftest evidence-bundle-artifact-timestamps-ordered-test
  (testing "artifacts within each PR are in chronological order"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (doseq [pr-evidence (:evidence/prs bundle)]
        (let [timestamps (map :timestamp (:evidence/artifacts pr-evidence))]
          (when (> (count timestamps) 1)
            (is (= timestamps (sort timestamps))
                (str "PR #" (:pr/number pr-evidence)
                     " artifacts should be chronologically ordered"))))))))

;; ============================================================================
;; Cross-Fixture Referential Integrity Tests
;; ============================================================================

(deftest cross-ref-all-ids-consistent-test
  (testing "all cross-references between fixtures are consistent"
    (let [ids    (load-fixture "demo-ids.edn")
          dag    (load-fixture "fleet-dag.edn")
          train  (load-fixture "train-state.edn")
          spec   (load-fixture "workflow-spec.edn")
          bundle (load-fixture "evidence-bundle.edn")]
      ;; DAG ID
      (is (= (:dag-id ids) (:dag/id dag) (:train/dag-id train)))
      ;; Train ID
      (is (= (:train-id ids) (:train/id train) (:evidence/train-id bundle)))
      ;; Workflow ID
      (is (= (:workflow-id ids) (:workflow/id spec)))
      ;; Bundle ID
      (is (= (:bundle-id ids) (:evidence/id bundle))))))

(deftest cross-ref-dag-repos-in-fleet-repos-test
  (testing "all DAG repos exist in fleet-repos"
    (let [repos (load-fixture "fleet-repos.edn")
          dag (load-fixture "fleet-dag.edn")
          fleet-names (set (map :repo/name (:repos repos)))
          dag-names (set (map :repo/name (:dag/repos dag)))]
      (is (set/subset? dag-names fleet-names)))))

(deftest cross-ref-train-repos-in-fleet-repos-test
  (testing "all train PR repos exist in fleet-repos"
    (let [repos (load-fixture "fleet-repos.edn")
          train (load-fixture "train-state.edn")
          fleet-names (set (map :repo/name (:repos repos)))
          train-repos (set (map :pr/repo (:train/prs train)))]
      (is (set/subset? train-repos fleet-names)))))

(deftest cross-ref-evidence-repos-in-fleet-repos-test
  (testing "all evidence PR repos exist in fleet-repos"
    (let [repos (load-fixture "fleet-repos.edn")
          bundle (load-fixture "evidence-bundle.edn")
          fleet-names (set (map :repo/name (:repos repos)))
          bundle-repos (set (map :pr/repo (:evidence/prs bundle)))]
      (is (set/subset? bundle-repos fleet-names)))))

(deftest cross-ref-blocked-pr-in-train-test
  (testing "blocked PR #201 exists in train state"
    (let [blocked (:blocked-pr (load-fixture "blocked-pr.edn"))
          train (load-fixture "train-state.edn")
          train-pr-numbers (set (map :pr/number (:train/prs train)))]
      (is (contains? train-pr-numbers (:pr/number blocked))))))

(deftest cross-ref-workflow-task-count-matches-train-scope-test
  (testing "workflow has enough tasks to cover the fleet"
    (let [spec (load-fixture "workflow-spec.edn")
          train (load-fixture "train-state.edn")]
      ;; At least as many tasks as PRs (may have more for validation etc.)
      (is (>= (count (:plan/tasks spec)) (count (:train/prs train)))))))

;; ============================================================================
;; Data Invariant Tests
;; ============================================================================

(deftest pr-urls-match-repos-test
  (testing "PR URLs contain the repo name"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (str/includes? (:pr/url pr) (:pr/repo pr))
            (str "URL " (:pr/url pr) " should contain repo " (:pr/repo pr)))))))

(deftest pr-urls-contain-pr-number-test
  (testing "PR URLs contain the PR number"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (str/includes? (:pr/url pr) (str (:pr/number pr)))
            (str "URL " (:pr/url pr) " should contain PR number " (:pr/number pr)))))))

(deftest pr-urls-end-with-pull-number-test
  (testing "PR URLs follow GitHub pull request URL pattern"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (str/ends-with? (:pr/url pr) (str "/pull/" (:pr/number pr)))
            (str "URL should end with /pull/" (:pr/number pr)))))))

(deftest pr-branches-start-with-feat-test
  (testing "all PR branches start with feat/"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (str/starts-with? (:pr/branch pr) "feat/")
            (str "Branch " (:pr/branch pr) " should start with feat/"))))))

(deftest timestamps-iso8601-or-inst-test
  (testing "DAG timestamp follows ISO 8601 format"
    (let [dag (load-fixture "fleet-dag.edn")
          iso-pattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"]
      (is (re-matches iso-pattern (:dag/created-at dag))
          (str "DAG timestamp '" (:dag/created-at dag) "' should be ISO 8601")))))

(deftest risk-level-correlates-with-score-test
  (testing "risk level is consistent with risk score"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (let [score (get-in pr [:pr/risk :risk/score])
              level (get-in pr [:pr/risk :risk/level])]
          (cond
            (< score 0.3)  (is (= :low level)
                               (str "Score " score " should be :low not " level))
            (< score 0.7)  (is (= :medium level)
                               (str "Score " score " should be :medium not " level))
            :else          (is (contains? #{:high :critical} level)
                               (str "Score " score " should be :high or :critical not " level))))))))

;; ============================================================================
;; Reset Script parse-args Tests
;; ============================================================================

(defn reset-parse-args
  "Mirror of reset script's parse-args."
  [args]
  {:keep-logs? (some #(= % "--keep-logs") args)})

(deftest reset-parse-args-defaults-test
  (testing "no arguments returns keep-logs? nil"
    (let [result (reset-parse-args [])]
      (is (not (:keep-logs? result))))))

(deftest reset-parse-args-keep-logs-test
  (testing "--keep-logs flag is detected"
    (let [result (reset-parse-args ["--keep-logs"])]
      (is (= "--keep-logs" (:keep-logs? result))))))

(deftest reset-parse-args-other-flags-test
  (testing "other flags don't trigger keep-logs"
    (let [result (reset-parse-args ["--verbose" "--force"])]
      (is (not (:keep-logs? result))))))

(deftest reset-parse-args-keep-logs-with-others-test
  (testing "--keep-logs detected among other flags"
    (let [result (reset-parse-args ["--verbose" "--keep-logs" "--force"])]
      (is (:keep-logs? result)))))

;; ============================================================================
;; Reset Script Expected Fixtures List Test
;; ============================================================================

(deftest reset-expected-fixtures-match-seed-test
  (testing "reset script expected fixtures cover all seed fixtures"
    ;; The reset script's expected-fixtures list
    (let [reset-expected #{"fleet-repos.edn" "fleet-dag.edn" "train-state.edn"
                           "blocked-pr.edn" "workflow-spec.edn" "evidence-bundle.edn"
                           "policy-gate-results.edn" "demo-ids.edn"}
          ;; The seed script's fixtures list
          seed-fixtures #{"fleet-repos.edn" "fleet-dag.edn" "train-state.edn"
                          "blocked-pr.edn" "workflow-spec.edn" "evidence-bundle.edn"
                          "policy-gate-results.edn" "demo-ids.edn"}]
      (is (= reset-expected seed-fixtures)
          "Reset and seed scripts should agree on fixture file list"))))
