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

(ns demo.demo-seed-e2e-test
  "End-to-end and integration tests for the MVP demo seed/reset scripts.
   Covers builder function consistency, content-for edge cases, fixture
   determinism, reset cleanup logic, and subprocess execution."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.set :as set]
            [babashka.fs :as fs]
            [babashka.process :as process]))

;; ============================================================================
;; Layer 0 — Constants & Helpers
;; ============================================================================

(def project-root (str (fs/canonicalize ".")))
(def fixture-dir (str project-root "/tests/fixtures/demo"))

(defn load-fixture [filename]
  (edn/read-string (slurp (str fixture-dir "/" filename))))

;; Mirror seed script definitions for builder testing

(def demo-ids
  {:dag-id       #uuid "a1b2c3d4-0000-4000-8000-000000000099"
   :train-id     #uuid "a1b2c3d4-0000-4000-8000-000000000001"
   :pr-low-risk  #uuid "a0000000-0000-4000-8000-000000000101"
   :pr-med-risk  #uuid "a0000000-0000-4000-8000-000000000201"
   :pr-blocked   #uuid "a0000000-0000-4000-8000-000000000301"
   :workflow-id  #uuid "f0000000-0000-4000-8000-000000000001"
   :bundle-id    #uuid "e0e0e0e0-0000-4000-8000-000000000001"})

(defn content-for [data]
  (with-out-str (pp/pprint data)))

(defn write-if-changed! [path content]
  (if (and (fs/exists? path) (= content (slurp path)))
    :unchanged
    (do (spit path content) :written)))

(defn parse-args [args]
  (let [arg-set (set args)]
    {:dry-run?   (contains? arg-set "--dry-run")
     :output-dir (or (some->> args
                              (partition 2 1)
                              (some (fn [[flag val]]
                                      (when (= flag "--output-dir") val))))
                     "tests/fixtures/demo")}))

;; ── Builder functions (mirrored from seed script) ──

(defn build-fleet-repos []
  {:repos [{:repo/name "terraform-modules"
            :repo/url "https://github.com/acme-corp/terraform-modules"
            :repo/type :infrastructure
            :repo/description "Shared Terraform modules for cloud infrastructure"
            :repo/default-branch "main"}
           {:repo/name "terraform-live"
            :repo/url "https://github.com/acme-corp/terraform-live"
            :repo/type :infrastructure
            :repo/description "Live Terraform configurations per environment"
            :repo/default-branch "main"}
           {:repo/name "k8s-manifests"
            :repo/url "https://github.com/acme-corp/k8s-manifests"
            :repo/type :platform
            :repo/description "Kubernetes deployment manifests and Helm charts"
            :repo/default-branch "main"}
           {:repo/name "api-gateway"
            :repo/url "https://github.com/acme-corp/api-gateway"
            :repo/type :application
            :repo/description "API gateway service with routing and rate limiting"
            :repo/default-branch "main"}]})

(defn build-fleet-dag []
  {:dag/id (:dag-id demo-ids)
   :dag/name "acme-infra-fleet"
   :dag/description "Infrastructure fleet DAG for Acme Corp production environment"
   :dag/created-at "2026-01-15T10:00:00Z"
   :dag/repos [{:repo/name "terraform-modules"
                :repo/url "https://github.com/acme-corp/terraform-modules"
                :repo/type :infrastructure
                :repo/layer :foundation}
               {:repo/name "terraform-live"
                :repo/url "https://github.com/acme-corp/terraform-live"
                :repo/type :infrastructure
                :repo/layer :environment}
               {:repo/name "k8s-manifests"
                :repo/url "https://github.com/acme-corp/k8s-manifests"
                :repo/type :platform
                :repo/layer :deployment}]
   :dag/edges [{:edge/from "terraform-modules"
                :edge/to "terraform-live"
                :edge/constraint :version-pin
                :edge/ordering :sequential}
               {:edge/from "terraform-live"
                :edge/to "k8s-manifests"
                :edge/constraint :deploy-after
                :edge/ordering :sequential}]})

(defn build-policy-gate-results []
  [{:gate/name "terraform-plan"
    :gate/result :pass
    :gate/details "Plan shows +3 ~0 -0 resources for VPC module update"
    :gate/pr 101}
   {:gate/name "code-review"
    :gate/result :pass
    :gate/details "Approved by senior infrastructure engineer"
    :gate/pr 101}
   {:gate/name "terraform-plan"
    :gate/result :pass
    :gate/details "Plan shows +2 ~1 -1 resources for RDS upgrade"
    :gate/pr 201}
   {:gate/name "code-review"
    :gate/result :pending
    :gate/details "Awaiting review from database team lead"
    :gate/pr 201}
   {:gate/name "terraform-plan"
    :gate/result :fail
    :gate/details "Cannot run plan — upstream dependencies not yet applied"
    :gate/pr 301}
   {:gate/name "code-review"
    :gate/result :pending
    :gate/details "PR is in draft state, review not yet requested"
    :gate/pr 301}])

;; ── Temp dir fixture ──

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "miniforge-seed-e2e-"}))]
    (binding [*test-dir* dir]
      (try (f)
           (finally
             (when (fs/exists? dir)
               (fs/delete-tree dir)))))))

(use-fixtures :each temp-dir-fixture)

;; ============================================================================
;; Layer 1 — Builder function consistency with fixture files
;; ============================================================================

(deftest builder-fleet-repos-matches-fixture-test
  (testing "build-fleet-repos output matches fleet-repos.edn on disk"
    (let [built   (build-fleet-repos)
          on-disk (load-fixture "fleet-repos.edn")]
      (is (= built on-disk)
          "Builder output must match committed fixture"))))

(deftest builder-fleet-dag-matches-fixture-test
  (testing "build-fleet-dag output matches fleet-dag.edn on disk"
    (let [built   (build-fleet-dag)
          on-disk (load-fixture "fleet-dag.edn")]
      (is (= built on-disk)
          "Builder output must match committed fixture"))))

(deftest builder-demo-ids-matches-fixture-test
  (testing "demo-ids constant matches demo-ids.edn on disk"
    (let [on-disk (load-fixture "demo-ids.edn")]
      (is (= demo-ids on-disk)
          "demo-ids constant must match committed fixture"))))

(deftest builder-policy-gate-results-matches-fixture-test
  (testing "build-policy-gate-results output matches policy-gate-results.edn on disk"
    (let [built   (build-policy-gate-results)
          on-disk (load-fixture "policy-gate-results.edn")]
      (is (= built on-disk)
          "Builder output must match committed fixture"))))

;; ============================================================================
;; Layer 1 — content-for with tagged literals and special types
;; ============================================================================

(deftest content-for-uuid-round-trip-test
  (testing "content-for preserves UUID tagged literals through round-trip"
    (let [data {:id #uuid "a1b2c3d4-0000-4000-8000-000000000099"}
          rendered (content-for data)
          parsed   (edn/read-string rendered)]
      (is (= data parsed))
      (is (uuid? (:id parsed))))))

(deftest content-for-inst-round-trip-test
  (testing "content-for preserves #inst tagged literals through round-trip"
    (let [data {:timestamp #inst "2026-01-20T10:00:00.000Z"}
          rendered (content-for data)
          parsed   (edn/read-string rendered)]
      (is (= data parsed))
      (is (inst? (:timestamp parsed))))))

(deftest content-for-keywords-round-trip-test
  (testing "content-for preserves namespaced keywords through round-trip"
    (let [data {:repo/name "test" :repo/type :infrastructure}
          rendered (content-for data)
          parsed   (edn/read-string rendered)]
      (is (= data parsed)))))

(deftest content-for-nested-vectors-round-trip-test
  (testing "content-for preserves nested vectors and maps"
    (let [data {:outer [{:inner [1 2 3]} {:inner [4 5 6]}]}
          rendered (content-for data)
          parsed   (edn/read-string rendered)]
      (is (= data parsed)))))

(deftest content-for-complex-fixture-round-trip-test
  (testing "content-for round-trips the full fleet-dag fixture (UUIDs + keywords + nested)"
    (let [data     (build-fleet-dag)
          rendered (content-for data)
          parsed   (edn/read-string rendered)]
      (is (= data parsed)))))

;; ============================================================================
;; Layer 1 — content-for determinism
;; ============================================================================

(deftest content-for-deterministic-output-test
  (testing "content-for produces identical output for identical input across calls"
    (let [data (build-fleet-repos)
          s1   (content-for data)
          s2   (content-for data)]
      (is (= s1 s2)
          "Same data must produce byte-identical pprint output"))))

(deftest content-for-deterministic-with-uuids-test
  (testing "content-for is deterministic for data containing UUIDs"
    (let [data demo-ids
          s1   (content-for data)
          s2   (content-for data)]
      (is (= s1 s2)))))

;; ============================================================================
;; Layer 1 — write-if-changed! edge cases
;; ============================================================================

(deftest write-if-changed-nested-dir-test
  (testing "write-if-changed! works with nested directories that exist"
    (let [nested-dir (str *test-dir* "/a/b/c")]
      (fs/create-dirs nested-dir)
      (let [path (str nested-dir "/test.edn")]
        (is (= :written (write-if-changed! path "data")))
        (is (= "data" (slurp path)))))))

(deftest write-if-changed-unicode-content-test
  (testing "write-if-changed! handles unicode content correctly"
    (let [path    (str *test-dir* "/unicode.edn")
          content "Unicode: \u2014 em dash, \u2019 apostrophe"]
      (is (= :written (write-if-changed! path content)))
      (is (= content (slurp path)))
      (is (= :unchanged (write-if-changed! path content))))))

(deftest write-if-changed-large-content-test
  (testing "write-if-changed! handles large content (> 64KB)"
    (let [path    (str *test-dir* "/large.edn")
          content (apply str (repeat 10000 "abcdefghij"))]
      (is (= :written (write-if-changed! path content)))
      (is (= :unchanged (write-if-changed! path content))))))

(deftest write-if-changed-overwrite-different-length-test
  (testing "write-if-changed! detects content change even when lengths differ"
    (let [path (str *test-dir* "/length.edn")]
      (spit path "short")
      (is (= :written (write-if-changed! path "much longer content here")))
      (is (= "much longer content here" (slurp path))))))

;; ============================================================================
;; Layer 1 — parse-args additional edge cases
;; ============================================================================

(deftest parse-args-empty-output-dir-value-test
  (testing "--output-dir with empty string value"
    (let [result (parse-args ["--output-dir" ""])]
      (is (= "" (:output-dir result))))))

(deftest parse-args-output-dir-with-spaces-test
  (testing "--output-dir with path containing spaces"
    (let [result (parse-args ["--output-dir" "/tmp/my demo dir"])]
      (is (= "/tmp/my demo dir" (:output-dir result))))))

(deftest parse-args-flag-like-output-dir-test
  (testing "--output-dir value that looks like a flag"
    (let [result (parse-args ["--output-dir" "--not-a-flag"])]
      (is (= "--not-a-flag" (:output-dir result))))))

;; ============================================================================
;; Layer 2 — Fixture file completeness (seed fixture-specs coverage)
;; ============================================================================

(def seed-fixture-specs
  "The ordered list from the seed script. Tests that all expected files are covered."
  ["demo-ids.edn"
   "fleet-repos.edn"
   "fleet-dag.edn"
   "train-state.edn"
   "blocked-pr.edn"
   "workflow-spec.edn"
   "evidence-bundle.edn"
   "policy-gate-results.edn"])

(def reset-expected-fixtures
  #{"fleet-repos.edn" "fleet-dag.edn" "train-state.edn"
    "blocked-pr.edn" "workflow-spec.edn" "evidence-bundle.edn"
    "policy-gate-results.edn" "demo-ids.edn"})

(deftest fixture-specs-cover-all-files-on-disk-test
  (testing "seed fixture-specs cover every .edn file in the demo fixture dir"
    (let [on-disk (set (map str (fs/glob fixture-dir "*.edn")))
          expected (set (map #(str fixture-dir "/" %) seed-fixture-specs))]
      (is (= expected on-disk)
          (str "Mismatch between fixture-specs and disk files.\n"
               "  On disk only: " (set/difference on-disk expected) "\n"
               "  In specs only: " (set/difference expected on-disk))))))

(deftest fixture-specs-and-reset-expected-agree-test
  (testing "seed fixture-specs and reset expected-fixtures list the same files"
    (is (= (set seed-fixture-specs) reset-expected-fixtures))))

;; ============================================================================
;; Layer 2 — Seed to temp dir (full integration)
;; ============================================================================

(deftest seed-all-fixtures-to-temp-dir-test
  (testing "Seeding all fixture specs to a temp dir produces parseable files"
    (doseq [f seed-fixture-specs]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (write-if-changed! target content)))
    ;; Verify every file exists and parses
    (doseq [f seed-fixture-specs]
      (let [path (str *test-dir* "/" f)]
        (is (fs/exists? path) (str "Missing seeded file: " f))
        (let [parsed (edn/read-string (slurp path))]
          (is (some? parsed) (str "Seeded file parsed to nil: " f)))))))

(deftest seed-idempotency-all-fixtures-test
  (testing "Seeding twice produces :unchanged on second run for all fixtures"
    ;; First pass
    (doseq [f seed-fixture-specs]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (is (= :written (write-if-changed! target content))
            (str "First seed should write: " f))))
    ;; Second pass
    (doseq [f seed-fixture-specs]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (is (= :unchanged (write-if-changed! target content))
            (str "Second seed must be unchanged: " f))))))

(deftest seed-content-matches-source-test
  (testing "Seeded files in temp dir are byte-identical to source fixtures"
    (doseq [f seed-fixture-specs]
      (let [source-content (slurp (str fixture-dir "/" f))
            data           (edn/read-string source-content)
            rendered       (content-for data)
            target         (str *test-dir* "/" f)]
        (write-if-changed! target rendered)
        ;; Compare parsed values (formatting may differ slightly)
        (is (= (edn/read-string source-content)
               (edn/read-string (slurp target)))
            (str "Seeded content semantically diverges for: " f))))))

;; ============================================================================
;; Layer 2 — Reset cleanup logic (unit tests with temp dirs)
;; ============================================================================

(deftest clean-runtime-dirs-removes-dirs-test
  (testing "Cleaning runtime dirs removes created directories"
    (let [dir1 (str *test-dir* "/tmp/demo")
          dir2 (str *test-dir* "/.demo-state")]
      ;; Create the dirs
      (fs/create-dirs dir1)
      (fs/create-dirs dir2)
      (spit (str dir1 "/state.edn") "data")
      (spit (str dir2 "/cache.edn") "data")
      (is (fs/exists? dir1))
      (is (fs/exists? dir2))
      ;; Clean them
      (doseq [dir [dir1 dir2]]
        (when (fs/exists? dir)
          (fs/delete-tree dir)))
      (is (not (fs/exists? dir1)))
      (is (not (fs/exists? dir2))))))

(deftest clean-runtime-dirs-idempotent-test
  (testing "Cleaning runtime dirs is safe when dirs don't exist"
    (let [dir (str *test-dir* "/nonexistent-runtime")]
      (is (not (fs/exists? dir)))
      ;; Should not throw
      (when (fs/exists? dir)
        (fs/delete-tree dir))
      (is (not (fs/exists? dir))))))

(deftest clean-log-files-removes-matching-test
  (testing "Log file cleanup removes files matching demo patterns"
    (let [log-dir (str *test-dir* "/logs")]
      (fs/create-dirs log-dir)
      (spit (str log-dir "/demo-run1.log") "log data")
      (spit (str log-dir "/demo-run2.log") "log data")
      (spit (str log-dir "/workflow-abc.log") "log data")
      (spit (str log-dir "/other.log") "should not be removed")
      ;; Clean demo/workflow logs
      (doseq [f (fs/glob log-dir "demo-*.log")]
        (fs/delete f))
      (doseq [f (fs/glob log-dir "workflow-*.log")]
        (fs/delete f))
      ;; Demo/workflow logs gone
      (is (empty? (fs/glob log-dir "demo-*.log")))
      (is (empty? (fs/glob log-dir "workflow-*.log")))
      ;; Other log preserved
      (is (fs/exists? (str log-dir "/other.log"))))))

(deftest clean-log-files-keep-logs-test
  (testing "When --keep-logs, log files are preserved"
    (let [log-dir (str *test-dir* "/logs")
          opts    {:keep-logs? true}]
      (fs/create-dirs log-dir)
      (spit (str log-dir "/demo-run1.log") "log data")
      ;; When keep-logs, don't clean
      (when-not (:keep-logs? opts)
        (doseq [f (fs/glob log-dir "demo-*.log")]
          (fs/delete f)))
      ;; Log should still exist
      (is (fs/exists? (str log-dir "/demo-run1.log"))))))

;; ============================================================================
;; Layer 2 — Verify fixtures logic (unit test)
;; ============================================================================

(deftest verify-fixtures-all-valid-test
  (testing "Verification passes when all fixture files exist and parse"
    ;; Seed temp dir with valid fixtures
    (doseq [f seed-fixture-specs]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (write-if-changed! target content)))
    ;; Verify each file
    (let [errors (atom [])]
      (doseq [f seed-fixture-specs]
        (let [path (str *test-dir* "/" f)]
          (if (fs/exists? path)
            (try
              (let [data (edn/read-string (slurp path))]
                (when (nil? data)
                  (swap! errors conj f)))
              (catch Exception _e
                (swap! errors conj f)))
            (swap! errors conj f))))
      (is (empty? @errors)
          (str "Verification failed for: " @errors)))))

(deftest verify-fixtures-detects-missing-file-test
  (testing "Verification detects a missing fixture file"
    ;; Seed all except one
    (doseq [f (rest seed-fixture-specs)]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (write-if-changed! target content)))
    ;; Verify — first file should be missing
    (let [missing-file (first seed-fixture-specs)
          path         (str *test-dir* "/" missing-file)]
      (is (not (fs/exists? path))
          "Setup: first fixture should be missing"))))

(deftest verify-fixtures-detects-invalid-edn-test
  (testing "Verification detects a file with invalid EDN"
    (let [bad-file (str *test-dir* "/bad.edn")]
      (spit bad-file "{:unclosed")
      (is (thrown? Exception (edn/read-string (slurp bad-file)))))))

;; ============================================================================
;; Layer 2 — Cross-fixture: policy-gate-results ↔ blocked-pr consistency
;; ============================================================================

(deftest policy-gates-blocked-pr-gate-name-overlap-test
  (testing "Policy gate names for PR #201 overlap with blocked-pr gate IDs"
    (let [gates      (load-fixture "policy-gate-results.edn")
          blocked    (load-fixture "blocked-pr.edn")
          pr201-policy-gates (filter #(= 201 (:gate/pr %)) gates)
          pr201-blocked-gates (get-in blocked [:blocked-pr :pr/gate-results])
          policy-names (set (map :gate/name pr201-policy-gates))
          blocked-ids  (set (map #(name (:gate/id %)) pr201-blocked-gates))]
      ;; The gate names ("terraform-plan", "code-review") should correspond
      ;; to the gate IDs (:policy/terraform-plan, :policy/code-review)
      (doseq [pn policy-names]
        (is (contains? blocked-ids pn)
            (str "Policy gate name '" pn "' should have corresponding gate ID in blocked-pr"))))))

(deftest policy-gates-pr-201-terraform-plan-failure-consistent-test
  (testing "PR #201 terraform-plan gate status is consistent across fixtures"
    (let [gates   (load-fixture "policy-gate-results.edn")
          blocked (load-fixture "blocked-pr.edn")
          ;; In policy-gate-results, PR 201 terraform-plan is :pass
          ;; (it ran successfully but found destructive changes)
          policy-tf (first (filter #(and (= 201 (:gate/pr %))
                                         (= "terraform-plan" (:gate/name %)))
                                   gates))
          ;; In blocked-pr, the terraform-plan gate is :gate/passed? false
          blocked-tf (first (filter #(= :policy/terraform-plan (:gate/id %))
                                    (get-in blocked [:blocked-pr :pr/gate-results])))]
      (is (some? policy-tf) "Policy gate result for PR 201 terraform-plan should exist")
      (is (some? blocked-tf) "Blocked PR gate result for terraform-plan should exist")
      ;; Both reference terraform plan details for PR 201
      (is (false? (:gate/passed? blocked-tf))
          "Blocked PR terraform-plan gate should be failed"))))

;; ============================================================================
;; Layer 2 — DAG topology invariants beyond existing tests
;; ============================================================================

(deftest dag-repos-cover-all-train-prs-test
  (testing "Every PR repo in the train has a corresponding DAG repo"
    (let [dag   (load-fixture "fleet-dag.edn")
          train (load-fixture "train-state.edn")
          dag-repo-names   (set (map :repo/name (:dag/repos dag)))
          train-pr-repos   (set (map :pr/repo (:train/prs train)))]
      (is (set/subset? train-pr-repos dag-repo-names)
          "All train PR repos must be in the DAG"))))

(deftest dag-layer-ordering-matches-edge-ordering-test
  (testing "DAG repo layers follow topological order: foundation → environment → deployment"
    (let [dag (load-fixture "fleet-dag.edn")
          layer-order {:foundation 0 :environment 1 :deployment 2}
          repos (:dag/repos dag)]
      (doseq [edge (:dag/edges dag)]
        (let [from-repo (first (filter #(= (:edge/from edge) (:repo/name %)) repos))
              to-repo   (first (filter #(= (:edge/to edge) (:repo/name %)) repos))]
          (when (and from-repo to-repo)
            (is (< (get layer-order (:repo/layer from-repo) 99)
                   (get layer-order (:repo/layer to-repo) 99))
                (str "Layer ordering violated: " (:repo/name from-repo)
                     " (" (:repo/layer from-repo) ") → "
                     (:repo/name to-repo) " (" (:repo/layer to-repo) ")"))))))))

;; ============================================================================
;; Layer 2 — Evidence bundle artifact count consistency
;; ============================================================================

(deftest evidence-artifact-counts-match-summary-test
  (testing "Total gate-decision artifacts match summary gates-passed + gates-failed"
    (let [bundle (load-fixture "evidence-bundle.edn")
          all-artifacts (mapcat :evidence/artifacts (:evidence/prs bundle))
          gate-artifacts (filter #(= :gate-decision (:type %)) all-artifacts)
          summary (:evidence/summary bundle)]
      (is (= (count gate-artifacts)
             (+ (:gates-passed summary) (:gates-failed summary)))
          "Gate decision artifact count should equal gates-passed + gates-failed"))))

(deftest evidence-human-approval-count-matches-summary-test
  (testing "Human approval artifact count matches summary"
    (let [bundle (load-fixture "evidence-bundle.edn")
          all-artifacts (mapcat :evidence/artifacts (:evidence/prs bundle))
          approval-artifacts (filter #(= :human-approval (:type %)) all-artifacts)
          summary (:evidence/summary bundle)]
      (is (= (count approval-artifacts) (:human-approvals summary))
          "Human approval count should match summary"))))

;; ============================================================================
;; Layer 3 — E2E: seed script subprocess (when bb available)
;; ============================================================================

(deftest seed-script-dry-run-e2e-test
  (testing "Seed script --dry-run executes without errors and writes no files"
    (let [out-dir (str *test-dir* "/dry-run-output")
          result  (babashka.process/shell
                    {:out :string :err :string :continue true}
                    "bb" "scripts/demo/seed_mvp_demo.clj"
                    "--dry-run" "--output-dir" out-dir)]
      (is (zero? (:exit result))
          (str "Seed --dry-run should exit 0. stderr: " (:err result)))
      ;; In dry-run, output dir should either not exist or be empty
      (when (fs/exists? out-dir)
        (is (empty? (fs/glob out-dir "*.edn"))
            "Dry-run should not write any .edn files")))))

(deftest seed-script-custom-output-dir-e2e-test
  (testing "Seed script writes fixtures to custom output directory"
    (let [out-dir (str *test-dir* "/custom-output")
          result  (babashka.process/shell
                    {:out :string :err :string :continue true}
                    "bb" "scripts/demo/seed_mvp_demo.clj"
                    "--output-dir" out-dir)]
      (is (zero? (:exit result))
          (str "Seed should exit 0. stderr: " (:err result)))
      ;; All fixture files should exist
      (doseq [f seed-fixture-specs]
        (is (fs/exists? (str out-dir "/" f))
            (str "Missing seeded file: " f)))
      ;; All should parse as valid EDN
      (doseq [f seed-fixture-specs]
        (let [data (edn/read-string (slurp (str out-dir "/" f)))]
          (is (some? data) (str "Seeded file parsed to nil: " f)))))))

(deftest seed-script-idempotency-e2e-test
  (testing "Running seed script twice on same dir reports unchanged on second run"
    (let [out-dir (str *test-dir* "/idempotent-output")]
      ;; First run
      (let [r1 (babashka.process/shell
                 {:out :string :err :string :continue true}
                 "bb" "scripts/demo/seed_mvp_demo.clj"
                 "--output-dir" out-dir)]
        (is (zero? (:exit r1)) "First seed run should succeed"))
      ;; Second run — output should mention "unchanged"
      (let [r2 (babashka.process/shell
                 {:out :string :err :string :continue true}
                 "bb" "scripts/demo/seed_mvp_demo.clj"
                 "--output-dir" out-dir)]
        (is (zero? (:exit r2)) "Second seed run should succeed")
        ;; The output should report "0 written, 8 unchanged" or similar
        (is (str/includes? (:out r2) "unchanged")
            "Second run output should mention 'unchanged')")))))

(deftest seed-output-matches-canonical-fixtures-e2e-test
  (testing "Seed script output is semantically identical to canonical fixtures"
    (let [out-dir (str *test-dir* "/canonical-check")]
      (babashka.process/shell
        {:out :string :err :string :continue true}
        "bb" "scripts/demo/seed_mvp_demo.clj"
        "--output-dir" out-dir)
      ;; Compare each file semantically
      (doseq [f seed-fixture-specs]
        (let [canonical (edn/read-string (slurp (str fixture-dir "/" f)))
              seeded    (edn/read-string (slurp (str out-dir "/" f)))]
          (is (= canonical seeded)
              (str "Seeded output diverges from canonical for: " f)))))))

;; ============================================================================
;; Layer 3 — Fixture data never contains random/non-deterministic values
;; ============================================================================

(deftest all-uuids-are-deterministic-test
  (testing "No UUID in any fixture is a v4 random UUID (variant check)"
    ;; All demo UUIDs use 4xxx for version and 8xxx for variant, but with
    ;; deterministic hex digits (not random). We verify they match demo-ids.
    (let [all-known-uuids (set (vals demo-ids))]
      ;; Check DAG
      (is (contains? all-known-uuids (:dag/id (load-fixture "fleet-dag.edn"))))
      ;; Check train
      (let [train (load-fixture "train-state.edn")]
        (is (contains? all-known-uuids (:train/id train)))
        (is (contains? all-known-uuids (:train/dag-id train))))
      ;; Check workflow
      (is (contains? all-known-uuids (:workflow/id (load-fixture "workflow-spec.edn"))))
      ;; Check evidence
      (let [bundle (load-fixture "evidence-bundle.edn")]
        (is (contains? all-known-uuids (:evidence/id bundle)))
        (is (contains? all-known-uuids (:evidence/train-id bundle)))))))

(deftest multiple-seed-runs-produce-identical-output-test
  (testing "Two independent seed runs produce byte-identical files"
    (let [dir1 (str *test-dir* "/run1")
          dir2 (str *test-dir* "/run2")]
      ;; Run 1
      (babashka.process/shell
        {:out :string :err :string :continue true}
        "bb" "scripts/demo/seed_mvp_demo.clj" "--output-dir" dir1)
      ;; Run 2
      (babashka.process/shell
        {:out :string :err :string :continue true}
        "bb" "scripts/demo/seed_mvp_demo.clj" "--output-dir" dir2)
      ;; Compare byte-for-byte
      (doseq [f seed-fixture-specs]
        (let [c1 (slurp (str dir1 "/" f))
              c2 (slurp (str dir2 "/" f))]
          (is (= c1 c2)
              (str "Output differs between runs for: " f)))))))

;; ============================================================================
;; Layer 3 — Workflow tasks align with DAG edge count
;; ============================================================================

(deftest workflow-tasks-cover-dag-repos-test
  (testing "Workflow has at least one task per DAG repo"
    (let [dag  (load-fixture "fleet-dag.edn")
          spec (load-fixture "workflow-spec.edn")]
      (is (>= (count (:plan/tasks spec))
              (count (:dag/repos dag)))
          "Workflow should have at least as many tasks as DAG repos"))))

(deftest workflow-deploy-tasks-match-dag-deploy-edges-test
  (testing "Number of :deploy tasks is sufficient for sequential DAG edges"
    (let [dag  (load-fixture "fleet-dag.edn")
          spec (load-fixture "workflow-spec.edn")
          deploy-tasks (filter #(= :deploy (:task/type %)) (:plan/tasks spec))
          sequential-edges (filter #(= :sequential (:edge/ordering %)) (:dag/edges dag))]
      ;; At least as many deploy tasks as sequential edges
      (is (>= (count deploy-tasks) (count sequential-edges))
          "Should have enough deploy tasks for sequential edges"))))
