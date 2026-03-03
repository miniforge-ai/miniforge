(ns demo.fixtures-test
  "Comprehensive tests for MVP demo fixtures and seed/reset script helpers.
   Validates EDN structure, schema conformance, cross-fixture referential integrity,
   stable identifiers, and helper function logic."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 0 — Constants & Helpers
;; ──────────────────────────────────────────────────────────────────────────────

(def project-root (str (fs/canonicalize ".")))
(def fixture-dir (str project-root "/tests/fixtures/demo"))

(def fixture-files
  ["fleet-repos.edn"
   "train-state.edn"
   "blocked-pr.edn"
   "workflow-spec.edn"
   "evidence-bundle.edn"])

(def stable-train-id    #uuid "a1b2c3d4-0000-4000-8000-000000000001")
(def stable-dag-id      #uuid "a1b2c3d4-0000-4000-8000-000000000099")
(def stable-evidence-id #uuid "e0e0e0e0-0000-4000-8000-000000000001")

(defn load-fixture [filename]
  (let [path (str fixture-dir "/" filename)]
    (edn/read-string (slurp path))))

(def fleet-repos     (delay (load-fixture "fleet-repos.edn")))
(def train-state     (delay (load-fixture "train-state.edn")))
(def blocked-pr      (delay (load-fixture "blocked-pr.edn")))
(def workflow-spec   (delay (load-fixture "workflow-spec.edn")))
(def evidence-bundle (delay (load-fixture "evidence-bundle.edn")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Seed/Reset helper functions (mirrored from scripts for unit testing)
;; ──────────────────────────────────────────────────────────────────────────────

(defn content-for [data]
  (with-out-str (pp/pprint data)))

(defn write-if-changed! [path content]
  (if (and (fs/exists? path) (= content (slurp path)))
    :unchanged
    (do (spit path content) :written)))

(defn compute-checksum [content]
  (format "%016x" (hash content)))

;; Temp dir fixture for file-system tests
(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "miniforge-demo-test-"}))]
    (binding [*test-dir* dir]
      (try (f)
           (finally
             (when (fs/exists? dir)
               (fs/delete-tree dir)))))))

(use-fixtures :each temp-dir-fixture)

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — Fixture file existence & EDN parse tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest all-fixture-files-exist-test
  (testing "All expected fixture files exist on disk"
    (doseq [f fixture-files]
      (let [path (str fixture-dir "/" f)]
        (is (fs/exists? path) (str "Missing fixture: " f))))))

(deftest all-fixtures-parse-as-valid-edn-test
  (testing "Every fixture file parses as valid, non-nil EDN"
    (doseq [f fixture-files]
      (let [data (load-fixture f)]
        (is (some? data) (str "Fixture parsed to nil: " f))
        (is (map? data) (str "Fixture should be a map: " f))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — fleet-repos.edn schema
;; ──────────────────────────────────────────────────────────────────────────────

(deftest fleet-repos-structure-test
  (testing "fleet-repos has :repos key with a vector of repo maps"
    (let [data @fleet-repos]
      (is (contains? data :repos))
      (is (vector? (:repos data)))
      (is (= 4 (count (:repos data)))))))

(deftest fleet-repos-schema-test
  (testing "Each repo has required keys with correct types"
    (doseq [repo (:repos @fleet-repos)]
      (is (string? (:repo/name repo))         (str "repo/name must be string: " (:repo/name repo)))
      (is (string? (:repo/url repo))           (str "repo/url must be string"))
      (is (keyword? (:repo/type repo))         (str "repo/type must be keyword"))
      (is (string? (:repo/description repo))   (str "repo/description must be string"))
      (is (string? (:repo/default-branch repo)) (str "repo/default-branch must be string")))))

(deftest fleet-repos-types-test
  (testing "Repo types are from the expected set"
    (let [valid-types #{:infrastructure :platform :application}]
      (doseq [repo (:repos @fleet-repos)]
        (is (contains? valid-types (:repo/type repo))
            (str "Unexpected repo type: " (:repo/type repo)))))))

(deftest fleet-repos-urls-test
  (testing "All repo URLs are valid GitHub HTTPS URLs"
    (doseq [repo (:repos @fleet-repos)]
      (is (str/starts-with? (:repo/url repo) "https://github.com/")
          (str "URL not a GitHub URL: " (:repo/url repo))))))

(deftest fleet-repos-unique-names-test
  (testing "All repo names are unique"
    (let [names (map :repo/name (:repos @fleet-repos))]
      (is (= (count names) (count (set names)))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — train-state.edn schema
;; ──────────────────────────────────────────────────────────────────────────────

(deftest train-state-top-level-keys-test
  (testing "Train state contains all required top-level keys"
    (let [data @train-state
          required-keys #{:train/id :train/name :train/description :train/dag-id
                          :train/status :train/prs :train/blocking-prs
                          :train/ready-to-merge :train/progress
                          :train/created-at :train/updated-at}]
      (doseq [k required-keys]
        (is (contains? data k) (str "Missing key: " k))))))

(deftest train-state-stable-ids-test
  (testing "Train uses stable deterministic UUIDs"
    (is (= stable-train-id (:train/id @train-state)))
    (is (= stable-dag-id   (:train/dag-id @train-state)))))

(deftest train-state-status-test
  (testing "Train status is a valid keyword"
    (let [valid-statuses #{:pending :reviewing :merging :merged :failed}]
      (is (contains? valid-statuses (:train/status @train-state))))))

(deftest train-state-prs-test
  (testing "Train has exactly 3 PRs"
    (is (= 3 (count (:train/prs @train-state)))))
  (testing "Each PR has required keys"
    (let [required-pr-keys #{:pr/repo :pr/number :pr/url :pr/branch :pr/title
                             :pr/status :pr/merge-order :pr/depends-on :pr/blocks
                             :pr/ci-status :pr/gate-results :pr/readiness-score
                             :pr/risk :pr/automation-tier}]
      (doseq [pr (:train/prs @train-state)]
        (doseq [k required-pr-keys]
          (is (contains? pr k) (str "PR " (:pr/number pr) " missing key: " k)))))))

(deftest train-state-pr-numbers-test
  (testing "PR numbers are 101, 201, 301 in that order"
    (is (= [101 201 301] (mapv :pr/number (:train/prs @train-state))))))

(deftest train-state-merge-order-test
  (testing "Merge order is sequential 1, 2, 3"
    (is (= [1 2 3] (mapv :pr/merge-order (:train/prs @train-state))))))

(deftest train-state-dependency-graph-test
  (testing "PR dependency graph is consistent"
    (let [prs (into {} (map (juxt :pr/number identity) (:train/prs @train-state)))]
      ;; PR 101 depends on nothing
      (is (= [] (:pr/depends-on (prs 101))))
      ;; PR 201 depends on 101
      (is (= [101] (:pr/depends-on (prs 201))))
      ;; PR 301 depends on 101 and 201
      (is (= [101 201] (:pr/depends-on (prs 301))))))
  (testing "Blocks are the inverse of depends-on"
    (let [prs (into {} (map (juxt :pr/number identity) (:train/prs @train-state)))]
      (is (= [201 301] (:pr/blocks (prs 101))))
      (is (= [301]     (:pr/blocks (prs 201))))
      (is (= []        (:pr/blocks (prs 301)))))))

(deftest train-state-pr-statuses-test
  (testing "PR statuses match the demo scenario"
    (let [prs (into {} (map (juxt :pr/number identity) (:train/prs @train-state)))]
      (is (= :approved  (:pr/status (prs 101))))
      (is (= :reviewing (:pr/status (prs 201))))
      (is (= :draft     (:pr/status (prs 301)))))))

(deftest train-state-ci-statuses-test
  (testing "CI statuses match the demo scenario"
    (let [prs (into {} (map (juxt :pr/number identity) (:train/prs @train-state)))]
      (is (= :passed  (:pr/ci-status (prs 101))))
      (is (= :running (:pr/ci-status (prs 201))))
      (is (= :pending (:pr/ci-status (prs 301)))))))

(deftest train-state-readiness-scores-test
  (testing "Readiness scores are between 0 and 1"
    (doseq [pr (:train/prs @train-state)]
      (is (<= 0.0 (:pr/readiness-score pr) 1.0)
          (str "PR " (:pr/number pr) " score out of range"))))
  (testing "Readiness scores decrease with merge order (higher risk = lower score)"
    (let [scores (mapv :pr/readiness-score (:train/prs @train-state))]
      (is (> (first scores) (second scores)))
      (is (> (second scores) (nth scores 2))))))

(deftest train-state-risk-structure-test
  (testing "Each PR risk has score, level, and factors"
    (doseq [pr (:train/prs @train-state)]
      (let [risk (:pr/risk pr)]
        (is (number? (:risk/score risk)))
        (is (keyword? (:risk/level risk)))
        (is (vector? (:risk/factors risk)))))))

(deftest train-state-gate-results-test
  (testing "Gate results have required structure"
    (doseq [pr (:train/prs @train-state)]
      (doseq [gate (:pr/gate-results pr)]
        (is (keyword? (:gate/id gate)))
        (is (keyword? (:gate/type gate)))
        (is (boolean? (:gate/passed? gate)))
        (is (string? (:gate/message gate)))
        (is (inst? (:gate/timestamp gate))))))
  (testing "PR 101 has all gates passed"
    (let [pr101 (first (:train/prs @train-state))]
      (is (every? :gate/passed? (:pr/gate-results pr101)))))
  (testing "PR 201 has at least one failed gate"
    (let [pr201 (second (:train/prs @train-state))]
      (is (some (complement :gate/passed?) (:pr/gate-results pr201))))))

(deftest train-state-progress-test
  (testing "Progress totals are consistent"
    (let [{:keys [total merged approved pending failed]} (:train/progress @train-state)]
      (is (= 3 total))
      (is (= total (+ merged approved pending failed))))))

(deftest train-state-blocking-and-ready-test
  (testing "Blocking PRs list is correct"
    (is (= [201] (:train/blocking-prs @train-state))))
  (testing "Ready-to-merge list is correct"
    (is (= [101] (:train/ready-to-merge @train-state)))))

(deftest train-state-timestamps-test
  (testing "created-at is before updated-at"
    (is (.before (:train/created-at @train-state)
                 (:train/updated-at @train-state)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — blocked-pr.edn schema
;; ──────────────────────────────────────────────────────────────────────────────

(deftest blocked-pr-structure-test
  (testing "Has :blocked-pr and :blocking-reasons keys"
    (let [data @blocked-pr]
      (is (contains? data :blocked-pr))
      (is (contains? data :blocking-reasons)))))

(deftest blocked-pr-matches-train-pr201-test
  (testing "Blocked PR number matches PR #201 in train state"
    (is (= 201 (get-in @blocked-pr [:blocked-pr :pr/number])))))

(deftest blocked-pr-has-intent-test
  (testing "Blocked PR has an intent specification"
    (let [intent (get-in @blocked-pr [:blocked-pr :pr/intent])]
      (is (some? intent))
      (is (keyword? (:intent/type intent)))
      (is (vector? (:intent/invariants intent)))
      (is (vector? (:intent/forbidden-actions intent)))
      (is (vector? (:intent/required-evidence intent))))))

(deftest blocked-pr-intent-invariants-test
  (testing "No-resource-deletion invariant is present"
    (let [invariants (get-in @blocked-pr [:blocked-pr :pr/intent :intent/invariants])]
      (is (= 1 (count invariants)))
      (is (= :no-resource-deletion (:invariant/id (first invariants)))))))

(deftest blocked-pr-blocking-reasons-test
  (testing "There are exactly 2 blocking reasons"
    (is (= 2 (count (:blocking-reasons @blocked-pr)))))
  (testing "Reasons include gate failure and dependency not merged"
    (let [reasons (set (map :reason (:blocking-reasons @blocked-pr)))]
      (is (contains? reasons :gate-not-passed))
      (is (contains? reasons :dependency-not-merged)))))

(deftest blocked-pr-gate-details-test
  (testing "Failed gate has details with deleted resources"
    (let [gates (get-in @blocked-pr [:blocked-pr :pr/gate-results])
          failed (first (filter (complement :gate/passed?) gates))]
      (is (some? failed))
      (is (= :policy/terraform-plan (:gate/id failed)))
      (is (some? (:gate/details failed)))
      (is (vector? (get-in failed [:gate/details :deleted-resources]))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — workflow-spec.edn schema
;; ──────────────────────────────────────────────────────────────────────────────

(deftest workflow-spec-top-level-test
  (testing "Workflow spec has required top-level keys"
    (let [data @workflow-spec]
      (is (= "1.0.0" (:workflow/spec-version data)))
      (is (= :full-sdlc (:workflow/type data)))
      (is (string? (:spec/title data)))
      (is (string? (:spec/description data)))
      (is (string? (:spec/intent data))))))

(deftest workflow-spec-constraints-test
  (testing "Constraints are a non-empty vector of strings"
    (let [constraints (:spec/constraints @workflow-spec)]
      (is (vector? constraints))
      (is (pos? (count constraints)))
      (is (every? string? constraints)))))

(deftest workflow-spec-acceptance-criteria-test
  (testing "Acceptance criteria are a non-empty vector of strings"
    (let [criteria (:spec/acceptance-criteria @workflow-spec)]
      (is (vector? criteria))
      (is (pos? (count criteria)))
      (is (every? string? criteria)))))

(deftest workflow-spec-tasks-test
  (testing "Plan has exactly 5 tasks"
    (is (= 5 (count (:plan/tasks @workflow-spec)))))
  (testing "Each task has required keys"
    (doseq [task (:plan/tasks @workflow-spec)]
      (is (keyword? (:task/id task)))
      (is (string? (:task/description task)))
      (is (keyword? (:task/type task)))
      (is (vector? (:task/dependencies task))))))

(deftest workflow-spec-task-types-test
  (testing "Task types are from the expected set"
    (let [valid-types #{:implement :test :configure :review :deploy}]
      (doseq [task (:plan/tasks @workflow-spec)]
        (is (contains? valid-types (:task/type task))
            (str "Unexpected task type: " (:task/type task)))))))

(deftest workflow-spec-task-dag-test
  (testing "First task has no dependencies"
    (is (= [] (:task/dependencies (first (:plan/tasks @workflow-spec))))))
  (testing "Task dependencies form a valid DAG (all referenced tasks exist)"
    (let [tasks (:plan/tasks @workflow-spec)
          task-ids (set (map :task/id tasks))]
      (doseq [task tasks]
        (doseq [dep (:task/dependencies task)]
          (is (contains? task-ids dep)
              (str "Task " (:task/id task) " references unknown dep: " dep)))))))

(deftest workflow-spec-task-order-test
  (testing "Tasks are in topological order (deps always precede dependents)"
    (let [tasks (:plan/tasks @workflow-spec)
          id->idx (into {} (map-indexed (fn [i t] [(:task/id t) i]) tasks))]
      (doseq [task tasks]
        (doseq [dep (:task/dependencies task)]
          (is (< (id->idx dep) (id->idx (:task/id task)))
              (str (:task/id task) " appears before its dep " dep)))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — evidence-bundle.edn schema
;; ──────────────────────────────────────────────────────────────────────────────

(deftest evidence-bundle-ids-test
  (testing "Evidence bundle uses stable IDs"
    (is (= stable-evidence-id (:evidence/id @evidence-bundle)))
    (is (= stable-train-id    (:evidence/train-id @evidence-bundle)))))

(deftest evidence-bundle-prs-test
  (testing "Evidence bundle covers all 3 PRs"
    (is (= 3 (count (:evidence/prs @evidence-bundle)))))
  (testing "PR numbers match the train PRs"
    (is (= #{101 201 301}
           (set (map :pr/number (:evidence/prs @evidence-bundle)))))))

(deftest evidence-bundle-artifacts-structure-test
  (testing "Each evidence PR has artifacts with required keys"
    (doseq [pr (:evidence/prs @evidence-bundle)]
      (is (vector? (:evidence/artifacts pr))
          (str "PR " (:pr/number pr) " artifacts should be a vector"))
      (doseq [artifact (:evidence/artifacts pr)]
        (is (keyword? (:type artifact)))
        (is (string? (:content artifact)))
        (is (string? (:hash artifact)))
        (is (inst? (:timestamp artifact)))))))

(deftest evidence-bundle-hashes-test
  (testing "All artifact hashes are sha256 prefixed"
    (doseq [pr (:evidence/prs @evidence-bundle)]
      (doseq [artifact (:evidence/artifacts pr)]
        (is (str/starts-with? (:hash artifact) "sha256:")
            (str "Hash should start with sha256: — got " (:hash artifact)))))))

(deftest evidence-bundle-summary-test
  (testing "Evidence summary matches fixture content"
    (let [summary (:evidence/summary @evidence-bundle)]
      (is (= 3 (:total-prs summary)))
      (is (= 2 (:gates-passed summary)))
      (is (= 1 (:gates-failed summary)))
      (is (= 1 (:human-approvals summary)))
      (is (= 0 (:semantic-violations summary))))))

(deftest evidence-bundle-version-test
  (testing "Miniforge version is present"
    (is (string? (:evidence/miniforge-version @evidence-bundle)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 2 — Cross-fixture referential integrity
;; ──────────────────────────────────────────────────────────────────────────────

(deftest cross-ref-train-repos-test
  (testing "All PR repos in train-state reference repos in fleet-repos"
    (let [fleet-repo-names (set (map :repo/name (:repos @fleet-repos)))
          train-pr-repos   (set (map :pr/repo (:train/prs @train-state)))]
      (doseq [repo train-pr-repos]
        (is (contains? fleet-repo-names repo)
            (str "Train PR repo not in fleet: " repo))))))

(deftest cross-ref-blocked-pr-in-train-test
  (testing "Blocked PR exists in train-state PRs"
    (let [blocked-num (get-in @blocked-pr [:blocked-pr :pr/number])
          train-nums  (set (map :pr/number (:train/prs @train-state)))]
      (is (contains? train-nums blocked-num)))))

(deftest cross-ref-blocked-pr-in-blocking-list-test
  (testing "Blocked PR number appears in train's blocking-prs"
    (let [blocked-num (get-in @blocked-pr [:blocked-pr :pr/number])]
      (is (some #{blocked-num} (:train/blocking-prs @train-state))))))

(deftest cross-ref-evidence-train-id-test
  (testing "Evidence bundle references the correct train"
    (is (= (:train/id @train-state)
           (:evidence/train-id @evidence-bundle)))))

(deftest cross-ref-evidence-pr-repos-test
  (testing "All evidence PR repos are in fleet-repos"
    (let [fleet-repo-names (set (map :repo/name (:repos @fleet-repos)))]
      (doseq [pr (:evidence/prs @evidence-bundle)]
        (is (contains? fleet-repo-names (:pr/repo pr))
            (str "Evidence PR repo not in fleet: " (:pr/repo pr)))))))

(deftest cross-ref-evidence-pr-numbers-test
  (testing "Evidence PR numbers match train PR numbers"
    (is (= (set (map :pr/number (:train/prs @train-state)))
           (set (map :pr/number (:evidence/prs @evidence-bundle)))))))

(deftest cross-ref-evidence-summary-consistency-test
  (testing "Evidence summary gate counts match actual gate results in train"
    (let [all-gates (mapcat :pr/gate-results (:train/prs @train-state))
          passed    (count (filter :gate/passed? all-gates))
          failed    (count (remove :gate/passed? all-gates))
          summary   (:evidence/summary @evidence-bundle)]
      ;; Note: PR 301 has empty gates, so only 101 and 201 contribute
      (is (= passed (:gates-passed summary)))
      (is (= failed (:gates-failed summary))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 2 — Seed/Reset helper function unit tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest content-for-produces-readable-edn-test
  (testing "content-for produces a string that round-trips through EDN"
    (let [data {:foo "bar" :baz [1 2 3]}
          s    (content-for data)
          back (edn/read-string s)]
      (is (string? s))
      (is (= data back)))))

(deftest content-for-handles-empty-map-test
  (testing "content-for handles an empty map"
    (let [s (content-for {})]
      (is (= {} (edn/read-string s))))))

(deftest compute-checksum-deterministic-test
  (testing "compute-checksum returns the same value for the same input"
    (let [content "hello world"
          c1 (compute-checksum content)
          c2 (compute-checksum content)]
      (is (= c1 c2))))
  (testing "compute-checksum returns different values for different inputs"
    (is (not= (compute-checksum "hello") (compute-checksum "world")))))

(deftest compute-checksum-format-test
  (testing "Checksum is a 16-character hex string"
    (let [checksum (compute-checksum "test content")]
      (is (= 16 (count checksum)))
      (is (re-matches #"[0-9a-f]{16}" checksum)))))

(deftest write-if-changed-creates-new-file-test
  (testing "write-if-changed! creates a new file and returns :written"
    (let [path (str *test-dir* "/new-file.edn")]
      (is (= :written (write-if-changed! path "content")))
      (is (= "content" (slurp path))))))

(deftest write-if-changed-skips-unchanged-test
  (testing "write-if-changed! returns :unchanged when content matches"
    (let [path (str *test-dir* "/same-file.edn")]
      (spit path "content")
      (is (= :unchanged (write-if-changed! path "content"))))))

(deftest write-if-changed-overwrites-different-test
  (testing "write-if-changed! overwrites and returns :written when content differs"
    (let [path (str *test-dir* "/diff-file.edn")]
      (spit path "old content")
      (is (= :written (write-if-changed! path "new content")))
      (is (= "new content" (slurp path))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 2 — Round-trip (seed) integration test
;; ──────────────────────────────────────────────────────────────────────────────

(deftest fixture-round-trip-test
  (testing "All fixtures round-trip: read → pprint → parse → identical"
    (doseq [f fixture-files]
      (let [original (load-fixture f)
            rendered (content-for original)
            parsed   (edn/read-string rendered)]
        (is (= original parsed)
            (str "Round-trip failed for " f))))))

(deftest seed-to-temp-dir-test
  (testing "Seeding to a temp directory writes all fixture files"
    (doseq [f fixture-files]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (write-if-changed! target content)))
    ;; Verify all files exist and parse
    (doseq [f fixture-files]
      (let [path (str *test-dir* "/" f)]
        (is (fs/exists? path) (str "Seeded file missing: " f))
        (let [data (edn/read-string (slurp path))]
          (is (some? data) (str "Seeded file parsed to nil: " f)))))))

(deftest seed-idempotency-test
  (testing "Running seed twice produces :unchanged on second run"
    ;; First run
    (doseq [f fixture-files]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (is (= :written (write-if-changed! target content)))))
    ;; Second run
    (doseq [f fixture-files]
      (let [data    (load-fixture f)
            content (content-for data)
            target  (str *test-dir* "/" f)]
        (is (= :unchanged (write-if-changed! target content))
            (str "Second seed should be unchanged for: " f))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 2 — Edge cases
;; ──────────────────────────────────────────────────────────────────────────────

(deftest empty-string-checksum-test
  (testing "Checksum of empty string is deterministic"
    (is (string? (compute-checksum "")))
    (is (= (compute-checksum "") (compute-checksum "")))))

(deftest write-if-changed-empty-content-test
  (testing "write-if-changed! handles empty string content"
    (let [path (str *test-dir* "/empty.edn")]
      (is (= :written (write-if-changed! path "")))
      (is (= "" (slurp path)))
      (is (= :unchanged (write-if-changed! path ""))))))

(deftest fixture-no-nil-values-in-required-fields-test
  (testing "No nil values in critical fields across all fixtures"
    ;; Train state PRs
    (doseq [pr (:train/prs @train-state)]
      (is (some? (:pr/repo pr)))
      (is (some? (:pr/number pr)))
      (is (some? (:pr/status pr))))
    ;; Evidence PRs
    (doseq [pr (:evidence/prs @evidence-bundle)]
      (is (some? (:pr/repo pr)))
      (is (some? (:pr/number pr)))
      (is (some? (:evidence/artifacts pr))))))

(deftest automation-tiers-test
  (testing "All PRs have valid automation tier"
    (let [valid-tiers #{:tier-1 :tier-2 :tier-3}]
      (doseq [pr (:train/prs @train-state)]
        (is (contains? valid-tiers (:pr/automation-tier pr))
            (str "Invalid tier for PR " (:pr/number pr)))))))