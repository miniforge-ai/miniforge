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

(ns ai.miniforge.etl.runner-test
  (:require
   [ai.miniforge.etl.runner :as runner]
   [ai.miniforge.schema.interface :as schema]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- repo-root
  "Locate the miniforge repo root by walking up from the test's cwd
   until a directory containing `workspace.edn` is found (the unique
   polylith-workspace marker). Lets the tests run no matter the cwd."
  []
  (loop [dir (io/file ".")]
    (cond
      (nil? dir)                              "."
      (.exists (io/file dir "workspace.edn")) (.getAbsolutePath dir)
      :else                                   (recur (.getParentFile (.getAbsoluteFile dir))))))

(deftest validate-pack-resolves-every-shipped-pack
  (testing "the three data-foundry packs in packs/data-foundry all resolve through the runner"
    (let [root (repo-root)]
      (doseq [[pipeline env stages]
              [["packs/data-foundry/github-data/pipelines/github-extract.edn"
                "packs/data-foundry/github-data/envs/local.edn"
                6]
               ["packs/data-foundry/gitlab-data/pipelines/gitlab-extract.edn"
                "packs/data-foundry/gitlab-data/envs/local.edn"
                9]
               ["packs/data-foundry/risk-data/pipelines/fred-risk-data.edn"
                "packs/data-foundry/risk-data/envs/fred-local.edn"
                8]]]
        (let [result (runner/validate-pack (str root "/" pipeline) (str root "/" env))]
          (is (schema/succeeded? result) (str "pack " pipeline " should validate"))
          (is (= stages (count (:pipeline/stages (:pipeline result))))
              (str "expected " stages " stages in " pipeline)))))))

(deftest validate-pack-surfaces-missing-files
  (testing "nonexistent pipeline path returns schema/failure, not an exception"
    (let [result (runner/validate-pack "/tmp/does-not-exist.edn" "/tmp/nope.edn")]
      (is (schema/failed? result))
      (is (string? (:error result))))))
