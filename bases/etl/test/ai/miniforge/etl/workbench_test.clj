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

(def ^:private source-hash
  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private pipeline-result
  {:success? true
   :pipeline-run
   {:pipeline-run/id #uuid "00000000-0000-0000-0000-000000000002"
    :pipeline-run/status :completed
    :pipeline-run/created-at "2026-07-18T12:00:00Z"
    :pipeline-run/completed-at "2026-07-18T12:01:00Z"
    :pipeline-run/stage-runs [{:stage/name "Ingest" :status :completed}]}})

(def ^:private baseline-config
  {:pipeline {:pipeline/name "Fixture"
              :pipeline/version "1.0.0"
              :pipeline/mode :full-refresh
              :pipeline/stages []}
   :environment {:env/name "test"}})

(def ^:private candidate-config
  (assoc-in baseline-config [:pipeline :pipeline/mode] :incremental))

(def ^:private base-opts
  {:experiment-id "etl.file-baseline"
   :label "baseline"
   :source-hash source-hash})

(deftest workbench-options-are-explicit
  (testing "the first missing required option is identified"
    (is (= "experiment-id" (sut/missing-option {})))
    (is (= "label" (sut/missing-option {:experiment-id "e"})))
    (is (= "source-hash" (sut/missing-option {:experiment-id "e" :label "b"})))
    (is (nil? (sut/missing-option base-opts))))
  (testing "source hashes use the canonical prefixed form"
    (is (sut/valid-source-hash? source-hash))
    (is (not (sut/valid-source-hash? "aaaa")))))

(deftest json-baseline-preserves-namespaced-factor-paths
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

(deftest baseline-source-hash-drift-is-rejected
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
