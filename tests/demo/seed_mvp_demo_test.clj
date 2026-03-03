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

;; Load seed script ns for function-level tests
;; We re-define the key functions/data here to avoid load-file side effects

(def demo-ids
  {:dag-id       #uuid "d0000000-0000-0000-0000-000000000001"
   :train-id     #uuid "t0000000-0000-0000-0000-000000000001"
   :pr-low-risk  #uuid "a0000000-0000-0000-0000-000000000001"
   :pr-med-risk  #uuid "a0000000-0000-0000-0000-000000000002"
   :pr-blocked   #uuid "a0000000-0000-0000-0000-000000000003"
   :workflow-id  #uuid "w0000000-0000-0000-0000-000000000001"
   :bundle-id    #uuid "e0000000-0000-0000-0000-000000000001"})

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
      (is (string? (:train/created-at train)))
      (is (vector? (:train/prs train))))))

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
        (is (uuid? (:pr/id pr)) "pr/id must be a UUID")
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
        (is (keyword? (:pr/risk-level pr)) "pr/risk-level must be a keyword")
        (is (integer? (:pr/lines-changed pr)) "pr/lines-changed must be an integer")))))

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

(deftest train-state-pr-100-approved-test
  (testing "PR #100 is approved with passing CI"
    (let [train (load-fixture "train-state.edn")
          pr100 (first (filter #(= 100 (:pr/number %)) (:train/prs train)))]
      (is (some? pr100))
      (is (= :approved (:pr/status pr100)))
      (is (= :passed (:pr/ci-status pr100)))
      (is (= :low (:pr/risk-level pr100)))
      (is (= 1 (:pr/merge-order pr100)))
      (is (empty? (:pr/depends-on pr100))))))

(deftest train-state-pr-200-reviewing-test
  (testing "PR #200 is under review with passing CI"
    (let [train (load-fixture "train-state.edn")
          pr200 (first (filter #(= 200 (:pr/number %)) (:train/prs train)))]
      (is (some? pr200))
      (is (= :reviewing (:pr/status pr200)))
      (is (= :passed (:pr/ci-status pr200)))
      (is (= :medium (:pr/risk-level pr200)))
      (is (= [100] (:pr/depends-on pr200))))))

(deftest train-state-pr-300-blocked-test
  (testing "PR #300 is blocked with CI failure and dependency"
    (let [train (load-fixture "train-state.edn")
          pr300 (first (filter #(= 300 (:pr/number %)) (:train/prs train)))]
      (is (some? pr300))
      (is (= :open (:pr/status pr300)))
      (is (= :failed (:pr/ci-status pr300)))
      (is (string? (:pr/ci-failure-reason pr300)))
      (is (= [200] (:pr/depends-on pr300)))
      (is (empty? (:pr/blocks pr300))))))

(deftest train-state-pr-300-blocking-reasons-test
  (testing "PR #300 has exactly 2 blocking reasons"
    (let [train (load-fixture "train-state.edn")
          pr300 (first (filter #(= 300 (:pr/number %)) (:train/prs train)))
          reasons (:pr/blocking-reasons pr300)]
      (is (= 2 (count reasons)))
      (is (= #{:ci-failed :dependency-unmerged}
             (set (map :reason reasons))))
      (doseq [r reasons]
        (is (string? (:detail r)))))))

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
  (testing "PR IDs match demo-ids"
    (let [train (load-fixture "train-state.edn")
          ids (load-fixture "demo-ids.edn")
          prs (:train/prs train)
          pr-by-num (into {} (map (juxt :pr/number :pr/id) prs))]
      (is (= (:pr-low-risk ids) (get pr-by-num 100)))
      (is (= (:pr-med-risk ids) (get pr-by-num 200)))
      (is (= (:pr-blocked ids) (get pr-by-num 300))))))

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
      (is (vector? (:spec/acceptance-criteria spec))))))

(deftest workflow-spec-id-matches-demo-ids-test
  (testing "workflow ID matches demo-ids :workflow-id"
    (let [spec (load-fixture "workflow-spec.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:workflow/id spec) (:workflow-id ids))))))

(deftest workflow-spec-constraints-non-empty-test
  (testing "workflow spec has at least one constraint"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (pos? (count (:spec/constraints spec))))
      (doseq [c (:spec/constraints spec)]
        (is (string? c) "Each constraint must be a string")
        (is (pos? (count c)) "Constraints must not be empty strings")))))

(deftest workflow-spec-acceptance-criteria-non-empty-test
  (testing "workflow spec has at least one acceptance criterion"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (pos? (count (:spec/acceptance-criteria spec))))
      (doseq [ac (:spec/acceptance-criteria spec)]
        (is (string? ac) "Each criterion must be a string")
        (is (pos? (count ac)) "Criteria must not be empty strings")))))

(deftest workflow-spec-type-test
  (testing "workflow type is :full-sdlc"
    (let [spec (load-fixture "workflow-spec.edn")]
      (is (= :full-sdlc (:workflow/type spec))))))

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

(deftest policy-gate-results-pr-100-all-pass-test
  (testing "PR #100 has all gates passing"
    (let [gates (load-fixture "policy-gate-results.edn")
          pr100-gates (filter #(= 100 (:gate/pr %)) gates)]
      (is (pos? (count pr100-gates)))
      (is (every? #(= :pass (:gate/result %)) pr100-gates)))))

(deftest policy-gate-results-pr-300-has-failures-test
  (testing "PR #300 has at least one failing gate"
    (let [gates (load-fixture "policy-gate-results.edn")
          pr300-gates (filter #(= 300 (:gate/pr %)) gates)]
      (is (pos? (count pr300-gates)))
      (is (some #(= :fail (:gate/result %)) pr300-gates)))))

(deftest policy-gate-results-pr-references-valid-test
  (testing "all gate PR numbers reference PRs in the train"
    (let [gates (load-fixture "policy-gate-results.edn")
          train (load-fixture "train-state.edn")
          pr-numbers (set (map :pr/number (:train/prs train)))
          gate-prs (set (map :gate/pr gates))]
      (is (set/subset? gate-prs pr-numbers)))))

;; ============================================================================
;; Evidence Bundle Fixture Tests
;; ============================================================================

(deftest evidence-bundle-structure-test
  (testing "evidence bundle has required keys"
    (let [bundle (load-fixture "evidence-bundle.edn")]
      (is (uuid? (:bundle/id bundle)))
      (is (uuid? (:bundle/train-id bundle)))
      (is (string? (:bundle/created-at bundle)))
      (is (vector? (:bundle/artifacts bundle)))
      (is (map? (:bundle/provenance bundle))))))

(deftest evidence-bundle-id-matches-demo-ids-test
  (testing "bundle ID matches demo-ids :bundle-id"
    (let [bundle (load-fixture "evidence-bundle.edn")
          ids (load-fixture "demo-ids.edn")]
      (is (= (:bundle/id bundle) (:bundle-id ids))))))

(deftest evidence-bundle-train-id-cross-ref-test
  (testing "bundle train-id references the PR train"
    (let [bundle (load-fixture "evidence-bundle.edn")
          train (load-fixture "train-state.edn")]
      (is (= (:bundle/train-id bundle) (:train/id train))))))

(deftest evidence-bundle-artifacts-test
  (testing "bundle has 3 artifacts all for PR #100"
    (let [bundle (load-fixture "evidence-bundle.edn")
          artifacts (:bundle/artifacts bundle)]
      (is (= 3 (count artifacts)))
      (doseq [a artifacts]
        (is (keyword? (:artifact/type a)))
        (is (string? (:artifact/summary a)))
        (is (= 100 (:artifact/pr a)))))))

(deftest evidence-bundle-artifact-types-test
  (testing "bundle contains expected artifact types"
    (let [bundle (load-fixture "evidence-bundle.edn")
          types (set (map :artifact/type (:bundle/artifacts bundle)))]
      (is (= #{:test-results :gate-decisions :merge-proof} types)))))

(deftest evidence-bundle-provenance-test
  (testing "provenance has required fields"
    (let [bundle (load-fixture "evidence-bundle.edn")
          prov (:bundle/provenance bundle)]
      (is (string? (:provenance/agent prov)))
      (is (string? (:provenance/version prov)))
      (is (string? (:provenance/decision prov))))))

;; ============================================================================
;; Demo IDs Fixture File Tests
;; ============================================================================

(deftest demo-ids-fixture-roundtrip-test
  (testing "demo-ids.edn file matches expected IDs"
    (let [ids (load-fixture "demo-ids.edn")]
      (is (= demo-ids ids)))))

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
      (is (= (:train-id ids) (:train/id train) (:bundle/train-id bundle)))
      ;; Workflow ID
      (is (= (:workflow-id ids) (:workflow/id spec)))
      ;; Bundle ID
      (is (= (:bundle-id ids) (:bundle/id bundle))))))

;; ============================================================================
;; Fixture File Existence Tests
;; ============================================================================

(deftest all-fixture-files-exist-test
  (testing "all expected fixture files exist"
    (let [expected ["fleet-dag.edn"
                    "train-state.edn"
                    "workflow-spec.edn"
                    "policy-gate-results.edn"
                    "evidence-bundle.edn"
                    "demo-ids.edn"]]
      (doseq [f expected]
        (is (.exists (io/file (str "tests/fixtures/demo/" f)))
            (str f " should exist"))))))

(deftest all-fixture-files-parseable-test
  (testing "all fixture files are valid EDN"
    (let [files ["fleet-dag.edn"
                 "train-state.edn"
                 "workflow-spec.edn"
                 "policy-gate-results.edn"
                 "evidence-bundle.edn"
                 "demo-ids.edn"]]
      (doseq [f files]
        (is (some? (load-fixture f))
            (str f " should parse as valid EDN"))))))

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

;; ============================================================================
;; DAG Topological Validity Tests
;; ============================================================================

(deftest fleet-dag-acyclic-test
  (testing "fleet DAG has no cycles"
    (let [dag (load-fixture "fleet-dag.edn")
          edges (:dag/edges dag)
          adj (reduce (fn [m {:keys [edge/from edge/to]}]
                        (update m from (fnil conj #{}) to))
                      {} edges)
          ;; Simple DFS cycle detection
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
;; Data Invariant Tests
;; ============================================================================

(deftest pr-urls-match-repos-test
  (testing "PR URLs contain the repo name"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (str/includes? (:pr/url pr) (:pr/repo pr))
            (str "URL " (:pr/url pr) " should contain repo " (:pr/repo pr)))))))

(deftest pr-lines-changed-positive-test
  (testing "all PRs have positive lines changed"
    (let [train (load-fixture "train-state.edn")]
      (doseq [pr (:train/prs train)]
        (is (pos? (:pr/lines-changed pr))
            (str "PR #" (:pr/number pr) " should have positive lines changed"))))))

(deftest timestamps-iso8601-format-test
  (testing "all timestamps follow ISO 8601 format"
    (let [dag (load-fixture "fleet-dag.edn")
          train (load-fixture "train-state.edn")
          bundle (load-fixture "evidence-bundle.edn")
          timestamps [(:dag/created-at dag)
                      (:train/created-at train)
                      (:bundle/created-at bundle)]
          iso-pattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"]
      (doseq [ts timestamps]
        (is (re-matches iso-pattern ts)
            (str "Timestamp '" ts "' should be ISO 8601"))))))