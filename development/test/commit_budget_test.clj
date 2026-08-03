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
(ns commit-budget-test
  "Pins `commit-budget`'s path-exclusion behaviour — which staged
   files count toward the commit-size budget and which are treated
   as bulk data. The gate runs on every commit; a silent widening
   (real code stops counting) or narrowing (fixture data starts
   blocking commits) of the exclusion set should fail here first."
  (:require [clojure.test :refer [deftest is testing]]))

(load-file "tasks/commit_budget.clj")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} excluded? (resolve 'commit-budget/excluded?))

(def ^{:stratum 0} file-reportable-count (resolve 'commit-budget/file-reportable-count))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resource-data-files-excluded-test
  (testing "data extensions under resources/ and messages/, nested or top-level"
    (is (excluded? "components/codex/resources/catalog.edn"))
    (is (excluded? "resources/catalog.json"))
    (is (excluded? "components/pr-lifecycle/resources/messages/en.edn"))
    (is (excluded? "seeds/initial.csv"))))

(deftest ^{:stratum 1} test-resources-dir-excluded-test
  (testing "test-resources/ is a fixture-data dir, same as resources/"
    (is (excluded? "components/codex/test-resources/notes/note-001.edn"))
    (is (excluded? "components/codex/test-resources/expected.json"))
    (is (excluded? "test-resources/sample.yaml"))))

(deftest ^{:stratum 1} suffixed-fixture-dirs-excluded-test
  (testing "dirs named *-fixture / *-fixtures match, not only the bare names"
    (is (excluded? "components/codex/test/codex-fixture/out.edn"))
    (is (excluded? "components/workflow/test/handoff-fixtures/phase.json"))
    (is (excluded? "test/fixtures/graph.edn"))
    (is (excluded? "test/fixture/graph.edn")))
  (testing "a dir merely *containing* 'fixtures' does not match"
    (is (not (excluded? "src/myfixtures/real_code.edn")))
    (is (not (excluded? "prefixtures/real_code.edn")))))

(deftest ^{:stratum 1} markdown-fixtures-excluded-test
  (testing ".md under fixture-data dirs is verbatim generator output"
    (is (excluded? "components/codex/test-resources/notes/note-001.md"))
    (is (excluded? "components/codex/test/codex-fixture/expected-note.md"))
    (is (excluded? "test/fixtures/rendered-report.md"))
    (is (excluded? "seeds/template.md")))
  (testing ".md elsewhere is reviewable prose and stays in budget"
    (is (not (excluded? "README.md")))
    (is (not (excluded? "docs/architecture/overview.md")))
    (is (not (excluded? "components/codex/resources/help.md")))
    (is (not (excluded? "components/pr-lifecycle/resources/messages/en/body.md")))))

(deftest ^{:stratum 1} code-shaped-files-stay-in-budget-test
  (testing "code-shaped EDN outside data dirs counts toward budget"
    (is (not (excluded? "deps.edn")))
    (is (not (excluded? "bb.edn")))
    (is (not (excluded? "workspace.edn")))
    (is (not (excluded? "components/codex/deps.edn")))
    (is (not (excluded? "work/n07-opsv-agent-budgets.spec.edn"))))
  (testing "behavior-as-data YAML/JSON outside data dirs counts"
    (is (not (excluded? ".github/workflows/ci.yml")))
    (is (not (excluded? "tsconfig.json"))))
  (testing "ordinary source files count"
    (is (not (excluded? "components/codex/src/ai/miniforge/codex/core.clj")))
    (is (not (excluded? "tasks/commit_budget.clj")))))

(deftest ^{:stratum 1} excluded-file-reports-zero-test
  (testing "an excluded fixture file contributes 0 reportable lines"
    (is (zero? (file-reportable-count
                {:path    "components/codex/test-resources/notes/note-001.md"
                 :added   ["# Note 001" "generated body line"]
                 :deleted []}))))
  (testing "the same content under src/ counts"
    (is (pos? (file-reportable-count
               {:path    "components/codex/src/note.md"
                :added   ["# Note 001" "generated body line"]
                :deleted []})))))
