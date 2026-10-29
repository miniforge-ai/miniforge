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
(ns test-runner-test
  "Pins which project-level tests `bb test:integration` runs, and that
   project test code stays off the miniforge project's :paths.

   Before this, the integration list was written by hand and 13 project
   test namespaces under projects/miniforge/test were never run anywhere:
   Polylith classified them as :src (they sat on :paths), and the hand
   list did not name them."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [test-runner :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} previously-unrun
  "Project tests the hand-written list never named."
  ["ai.miniforge.current-event-publication-integration-test"
   "ai.miniforge.event-journal-integration-test"
   "ai.miniforge.event-journal-ownership-integration-test"
   "ai.miniforge.file-durability.integration-test"
   "ai.miniforge.phase-opsv.recovery-receipts-integration-test"
   "ai.miniforge.workflow.chain-current-lifecycle-integration-test"
   "ai.miniforge.workflow.chain-edge-integration-test"
   "ai.miniforge.workflow.chain-publication-integration-test"
   "ai.miniforge.workflow.chain-required-integration-test"
   "ai.miniforge.workflow.chain-resources-integration-test"
   "ai.miniforge.workflow.chain-retention-integration-test"
   "ai.miniforge.workflow.opsv-phase-completion-integration-test"
   "ai.miniforge.workflow.partial-terminal-receipt-integration-test"])

(defn ^{:stratum 0} test-files-under
  "Namespace names of every *_test.clj file under `dir`, built from each
   relative path's segments (Path iteration, so either separator)."
  [dir]
  (->> (fs/glob dir "**_test.clj")
       (map #(->> (fs/components (fs/relativize dir %))
                  (map (fn [segment]
                         (-> (str segment)
                             (str/replace #"\.clj$" "")
                             (str/replace "_" "-"))))
                  (str/join ".")))
       set))

(defn ^{:stratum 0} project-deps
  [project]
  (edn/read-string (slurp (str "projects/" project "/deps.edn"))))

(deftest ^{:stratum 0} discovers-miniforge-core-project-test-test
  (is (= ["ai.miniforge.workflow.kernel-loader-integration-test"]
         (sut/discovered-project-tests "miniforge-core"))))

(deftest ^{:stratum 0} known-failing-skips-name-real-tests-test
  (testing "each skip names a discovered test, so a rename cannot leave a dead entry"
    (let [discovered (set (sut/discovered-project-tests "miniforge"))]
      (doseq [ns-name (keys sut/known-failing-project-tests)]
        (is (contains? discovered ns-name) ns-name))))
  (testing "each skip carries a reason"
    (is (every? (comp not str/blank?) (vals sut/known-failing-project-tests)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} discovers-every-miniforge-project-test-test
  (let [discovered (set (sut/discovered-project-tests "miniforge"))]
    (testing "the namespaces the hand-written list missed are discovered"
      (doseq [ns-name previously-unrun]
        (is (contains? discovered ns-name) ns-name)))
    (testing "every *_test.clj under test/ and integration/ is discovered"
      (is (= (into (test-files-under "projects/miniforge/test")
                   (test-files-under "projects/miniforge/integration"))
             discovered)))
    (testing "e2e tests (real claude CLI / Docker) are not"
      (is (empty? (filter (test-files-under "projects/miniforge/e2e")
                          discovered))))))

(deftest ^{:stratum 1} discovers-every-development-test-test
  (let [discovered (set (sut/discovered-development-tests))]
    (testing "every *_test.clj under development/test is discovered"
      (is (= (test-files-under "development/test") discovered)))
    (testing "including this namespace and the task tests CI never ran"
      (doseq [ns-name ["test-runner-test" "build-test" "deps-resources-test"
                       "ai.miniforge.dogfood-test"]]
        (is (contains? discovered ns-name) ns-name)))
    (testing "support namespaces are not run as tests"
      (is (not (contains? discovered "ai.miniforge.test-harness"))))))

(deftest ^{:stratum 1} project-test-code-is-not-on-paths-test
  (let [{:keys [paths aliases]} (project-deps "miniforge")]
    (testing "no test directory is a classpath root of the project's builds"
      (is (empty? (filter #{"test" "integration" "e2e"} paths))))
    (testing "integration tests stay out of :test, which Polylith runs under poly test :all"
      (is (empty? (filter #{"test" "integration" "e2e"}
                          (get-in aliases [:test :extra-paths])))))
    (testing "the :integration alias carries the test directories"
      (is (= ["test" "integration"]
             (get-in aliases [:integration :extra-paths]))))
    (testing "the :e2e alias carries test/, whose support namespaces e2e requires"
      (is (= ["test" "e2e"]
             (get-in aliases [:e2e :extra-paths]))))))
