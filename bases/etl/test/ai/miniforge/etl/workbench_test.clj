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

(ns ai.miniforge.etl.workbench-test
  (:require
   [ai.miniforge.etl.workbench :as sut]
   [ai.miniforge.schema.interface :as schema]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^:private source-hash
  "Canonical workload digest shared by baseline and candidate fixtures."
  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private pipeline-run-id
  "Stable run identity used to make snapshot IDs reproducible."
  #uuid "00000000-0000-0000-0000-000000000002")

(def ^:private pipeline-started-at
  "Stable fixture start instant."
  "2026-07-18T12:00:00Z")

(def ^:private pipeline-completed-at
  "Stable fixture completion instant."
  "2026-07-18T12:01:00Z")

(def ^:private pipeline-version
  "Fixture workflow version required by the snapshot contract."
  "1.0.0")

(defn- pipeline-result-fixture []
  {:success? true
   :pipeline-run
   {:pipeline-run/id pipeline-run-id
    :pipeline-run/status :completed
    :pipeline-run/created-at pipeline-started-at
    :pipeline-run/completed-at pipeline-completed-at
    :pipeline-run/stage-runs [{:stage/name "Ingest" :status :completed}]}})

(defn- resolved-run-config [mode]
  {:pipeline {:pipeline/name "Fixture"
              :pipeline/version pipeline-version
              :pipeline/mode mode
              :pipeline/stages []}
   :environment {:env/name "test"}})

(def ^:private pipeline-result
  "Completed pipeline result accepted by the adapter boundary."
  (pipeline-result-fixture))

(def ^:private baseline-config
  "Resolved-run baseline used by the file round-trip tests."
  (resolved-run-config :full-refresh))

(def ^:private candidate-config
  "Single-factor candidate required by the experiment invariant."
  (resolved-run-config :incremental))

(def ^:private base-opts
  "Required workbench provenance for the baseline projection."
  {:experiment-id "etl.file-baseline"
   :label "baseline"
   :source-hash source-hash})

;------------------------------------------------------------------------------ Layer 1

(deftest test-workbench-options-are-explicit
  (testing "given omitted workbench options → first missing option is identified"
    (is (= "experiment-id" (sut/missing-option {})))
    (is (= "label" (sut/missing-option {:experiment-id "e"})))
    (is (= "source-hash" (sut/missing-option {:experiment-id "e" :label "b"})))
    (is (nil? (sut/missing-option base-opts))))
  (testing "given source hashes → canonical prefixed form is required"
    (is (sut/valid-source-hash? source-hash))
    (is (not (sut/valid-source-hash? "aaaa")))))

(deftest test-json-baseline-preserves-namespaced-factor-paths
  (let [baseline-result (sut/project pipeline-result baseline-config base-opts)
        baseline-file   (doto (java.io.File/createTempFile "miniforge-etl-baseline" ".json")
                          (.deleteOnExit))]
    (spit baseline-file (json/generate-string (:snapshot baseline-result)))
    (let [candidate-result
          (sut/project pipeline-result candidate-config
                       (assoc base-opts
                              :label "candidate"
                              :baseline (.getAbsolutePath baseline-file)))]
      (is (schema/succeeded? baseline-result))
      (is (schema/succeeded? candidate-result))
      (is (= "[:pipeline :pipeline/mode]"
             (get-in candidate-result [:snapshot :variant :axes :factor_id]))))))

(deftest test-baseline-source-hash-drift-is-rejected
  (let [baseline-result (sut/project pipeline-result baseline-config base-opts)
        baseline-file   (doto (java.io.File/createTempFile "miniforge-etl-baseline" ".json")
                          (.deleteOnExit))]
    (spit baseline-file (json/generate-string (:snapshot baseline-result)))
    (let [result (sut/project pipeline-result candidate-config
                              (assoc base-opts
                                     :baseline (.getAbsolutePath baseline-file)
                                     :source-hash
                                     "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))]
      (is (schema/failed? result)))))
