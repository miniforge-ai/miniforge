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
   [clojure.edn :as edn]
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

(def ^{:stratum 0} ^:private ratio-input-records
  "Numerator and denominator for one date, plus a record with no date."
  [{:date "2024-01" :series_id "NUM" :value "10"}
   {:date "2024-01" :series_id "DEN" :value "100"}
   {:date nil :series_id "NUM" :value "5"}])

(defn- ^{:stratum 0} file-env
  "An env reading `input` and writing `output`, both local EDN files."
  [input output]
  {:env/name       "test"
   :env/connectors {:conn/in  {:connector/type :file}
                    :conn/out {:connector/type :file}}
   :env/stages     {"Ingest"  {:file/path input :file/format :edn}
                    "Publish" {:file/path output :file/format :edn}}})

(defn- ^{:stratum 0} override-ratio
  "A caller-supplied :derived-ratio that marks its output."
  [records _config]
  (conj (vec records) {:date "2024-01" :series_id "OVERRIDE" :value "1"}))

(defn- ^{:stratum 0} series-ids [records]
  (set (map :series_id records)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} invalid-rule-pipeline
  "A pipeline whose validate stage has one built-in rule descriptor `rule`."
  [rule]
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
                                 :pack/rules [rule]}}})
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

(def ^{:stratum 1} ^:private ratio-pipeline
  {:pipeline/name    "Ratio"
   :pipeline/version "0.1.0"
   :pipeline/mode    :full-refresh
   :pipeline/stages
   [(pack-stage "Ingest" :ingest [] [:ds/raw] [] {:stage/connector-ref :conn/in})
    (pack-stage "Derive" :transform [:ds/raw] [:ds/derived] ["Ingest"]
                {:stage/config {:stage/transform {:transform/type               :derived-ratio
                                                  :transform/numerator-series   "NUM"
                                                  :transform/denominator-series "DEN"
                                                  :transform/output-scale       100.0
                                                  :transform/output-series      "RATIO"}}})
    (pack-stage "Validate" :validate [:ds/derived] [:ds/checked] ["Derive"]
                {:stage/config {:stage/quality-pack {:pack/id    :dated
                                                     :pack/rules [{:rule/type :required :rule/field :date}]}}})
    (pack-stage "Publish" :publish [:ds/checked] [] ["Validate"] {:stage/connector-ref :conn/out})]
   :pipeline/input-datasets  []
   :pipeline/output-datasets []})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-validate-pack-preflights-resolved-pipeline
  (testing "given an unregistered transform, a pattern that does not compile, or a rule missing its field → validation fails with a preflight problem, no exception"
    (doseq [[pipeline-def expected] [[unresolved-transform-pipeline [[:transform/unresolved :no-such-transform]]]
                                     [(invalid-rule-pipeline {:rule/type :pattern :rule/field :title :rule/pattern "["})
                                      [[:quality-rule/invalid :pattern]]]
                                     [(invalid-rule-pipeline {:rule/type :required})
                                      [[:quality-rule/invalid :required]]]]]
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

(deftest ^{:stratum 2} test-run-pack-runs-built-in-transforms-and-rules
  (testing "given a file-to-file pack → the built-in ratio reaches the sink, the rule drops the undated record, and a caller transform overrides the built-in"
    (doseq [[context present absent] [[{} "RATIO" "OVERRIDE"]
                                      [{:transforms {:derived-ratio override-ratio}} "OVERRIDE" "RATIO"]]]
      (let [dir      (.toFile (java.nio.file.Files/createTempDirectory
                               "etl-run-pack" (make-array java.nio.file.attribute.FileAttribute 0)))
            input    (write-edn! dir "input.edn" ratio-input-records)
            output   (.getPath (io/file dir "output.edn"))
            pipeline (write-edn! dir "pipeline.edn" ratio-pipeline)
            env      (write-edn! dir "env.edn" (file-env input output))]
        (try
          (let [result    (runner/run-pack pipeline env context)
                published (edn/read-string (slurp output))]
            (is (schema/succeeded? result) (pr-str (:error result)))
            (is (contains? (series-ids published) present))
            (is (not (contains? (series-ids published) absent)))
            (is (every? :date published)))
          (finally
            (run! io/delete-file (reverse (file-seq dir)))))))))
