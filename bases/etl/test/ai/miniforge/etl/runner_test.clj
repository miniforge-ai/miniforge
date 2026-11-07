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

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} repo-root
  "Locate the miniforge repo root by walking up from the test's cwd
   until a directory containing `workspace.edn` is found (the unique
   polylith-workspace marker). Lets the tests run no matter the cwd."
  []
  (loop [dir (io/file ".")]
    (cond
      (nil? dir)                              "."
      (.exists (io/file dir "workspace.edn")) (.getAbsolutePath dir)
      :else                                   (recur (.getParentFile (.getAbsoluteFile dir))))))

(defn- ^{:stratum 0} pack-stage
  "A pipeline-EDN stage in the shape `pc/load-pipeline` reads: symbolic
   connector refs, dataset keywords, dependency names."
  [stage-name family inputs outputs deps extra]
  (merge {:stage/name            stage-name
          :stage/family          family
          :stage/input-datasets  inputs
          :stage/output-datasets outputs
          :stage/dependencies    deps}
         extra))

(def ^{:stratum 0} ^:private two-connector-env
  {:env/name "test"
   :env/connectors {:conn/github {:connector/type :github}
                    :conn/output {:connector/type :pipeline-output}}})

(defn- ^{:stratum 0} write-edn! [dir file-name value]
  (let [f (io/file dir file-name)]
    (spit f (pr-str value))
    (.getPath f)))

(deftest ^{:stratum 0} validate-pack-surfaces-missing-files
  (testing "nonexistent pipeline path returns schema/failure, not an exception"
    (let [result (runner/validate-pack "/tmp/does-not-exist.edn" "/tmp/nope.edn")]
      (is (schema/failed? result))
      (is (string? (:error result))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private invalid-pattern-pipeline
  {:pipeline/name    "Invalid Pattern"
   :pipeline/version "0.1.0"
   :pipeline/mode    :full-refresh
   :pipeline/stages
   [(pack-stage "Ingest" :ingest [] [:ds/raw] []
                {:stage/connector-ref :conn/github
                 :stage/config        {:github/resource "pulls"}})
    (pack-stage "Check" :validate [:ds/raw] [:ds/checked] ["Ingest"]
                {:stage/config {:stage/quality-pack
                                {:pack/id    :pattern-pack
                                 :pack/rules [{:rule/type :pattern :rule/field :title :rule/pattern "["}]}}})
    (pack-stage "Publish" :publish [:ds/checked] [] ["Check"]
                {:stage/connector-ref :conn/output})]
   :pipeline/input-datasets  []
   :pipeline/output-datasets []})

(deftest ^{:stratum 1} validate-pack-resolves-every-shipped-pack
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

(def ^{:stratum 1} ^:private unresolved-transform-pipeline
  {:pipeline/name    "Unresolved Transform"
   :pipeline/version "0.1.0"
   :pipeline/mode    :full-refresh
   :pipeline/stages
   [(pack-stage "Ingest" :ingest [] [:ds/raw] []
                {:stage/connector-ref :conn/github
                 :stage/config        {:github/resource "pulls"}})
    (pack-stage "Derive" :transform [:ds/raw] [:ds/derived] ["Ingest"]
                {:stage/config {:stage/transform {:transform/type :no-such-transform}}})
    (pack-stage "Publish" :publish [:ds/derived] [] ["Derive"]
                {:stage/connector-ref :conn/output})]
   :pipeline/input-datasets  []
   :pipeline/output-datasets []})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-validate-pack-preflights-resolved-pipeline
  (testing "given an unregistered transform or a pattern that does not compile → validation fails with a preflight problem, no exception"
    (doseq [[pipeline-def expected] [[unresolved-transform-pipeline [[:transform/unresolved :no-such-transform]]]
                                     [invalid-pattern-pipeline [[:quality-rule/invalid :pattern]]]]]
      (let [dir      (.toFile (java.nio.file.Files/createTempDirectory
                               "etl-preflight" (make-array java.nio.file.attribute.FileAttribute 0)))
            pipeline (write-edn! dir "pipeline.edn" pipeline-def)
            env      (write-edn! dir "env.edn" two-connector-env)]
        (try
          (let [result (runner/validate-pack pipeline env)]
            (is (schema/failed? result))
            (is (= expected
                   (mapv (juxt :preflight/problem :preflight/ref) (:preflight/problems result)))))
          (finally
            (run! io/delete-file (reverse (file-seq dir)))))))))
