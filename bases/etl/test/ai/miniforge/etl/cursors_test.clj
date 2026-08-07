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
(ns ai.miniforge.etl.cursors-test
  "Cross-run cursor behaviour, exercised through `runner/run-pack`.

   The packs here use the `:file` connector on both ends, so a whole
   run is local, deterministic, and offset-based: the source file's
   record count is the watermark, which makes `what did the second run
   read` a directly observable fact rather than something inferred from
   the cursor map.

   Strata: pack-shaped data and single-file I/O (0); the pack builder
   and the readers the assertions use over it (1); the tests (2). The
   EDN builders take the stage name rather than closing over it so they
   stay at the bottom — closing over it would push every caller up a
   layer and the file past its budget."
  (:require
   [ai.miniforge.cursor-store.interface :as cursor-store]
   [ai.miniforge.etl.cursors :as cursors]
   [ai.miniforge.etl.runner :as runner]
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; Pack-shaped data and single-file I/O.
(def ^{:stratum 0} ^:private ingest-stage-name "Ingest Rows")

(defn- ^{:stratum 0} pipeline-edn
  [stage-name mode]
  {:pipeline/name    "Cursor Test"
   :pipeline/version "0.1.0"
   :pipeline/mode    mode
   :pipeline/stages
   [{:stage/name            stage-name
     :stage/family          :ingest
     :stage/connector-ref   :conn/src
     :stage/input-datasets  []
     :stage/output-datasets [:ds/rows]
     :stage/dependencies    []}
    {:stage/name            "Publish"
     :stage/family          :publish
     :stage/connector-ref   :conn/sink
     :stage/input-datasets  [:ds/rows]
     :stage/output-datasets []
     :stage/dependencies    [stage-name]}]
   :pipeline/input-datasets  []
   :pipeline/output-datasets []})

(defn- ^{:stratum 0} env-edn
  [stage-name source-path out-path]
  {:env/name "local"
   :env/connectors {:conn/src  {:connector/type :file}
                    :conn/sink {:connector/type :file}}
   :env/stages {stage-name {:file/path source-path :file/format :edn}
                "Publish"  {:file/path out-path    :file/format :edn}}})

(defn- ^{:stratum 0} rows
  "n records, distinguishable so a re-ingest shows up as duplicates."
  [n]
  (mapv (fn [i] {:id i}) (range 1 (inc n))))

(defn- ^{:stratum 0} write-edn!
  [path value]
  (io/make-parents (io/file path))
  (spit path (pr-str value)))

(defn- ^{:stratum 0} execute-pack!
  [{:keys [pipeline-path env-path]}]
  (runner/run-pack pipeline-path env-path))

(defn- ^{:stratum 0} published
  [{:keys [out-path]}]
  (edn/read-string (slurp out-path)))

(defn- ^{:stratum 0} cursor-file
  [{:keys [pipeline-path]}]
  (let [f (io/file pipeline-path)]
    (io/file (.getParentFile f) ".cursors" (.getName f))))

(defn- ^{:stratum 0} persisted
  "One run's cursor map for a stage, as pipeline-runner reports it —
   keyed by stage id, which the store re-keys on save."
  [stage-name schema-name value]
  {(random-uuid) {:stage/name        stage-name
                  :stage/schema-name schema-name
                  :cursor {:cursor/type :offset :cursor/value value}}})

(defn- ^{:stratum 0} run-result
  "A pipeline-runner result of the given status, shaped as
   execute-pipeline returns it — a stage failure keeps :pipeline-run."
  [status cursors]
  (let [run {:pipeline-run/status status
             :pipeline-run/connector-cursors cursors}]
    (if (= :completed status)
      (schema/success :pipeline-run run)
      (schema/failure :pipeline-run "stage failed" {:pipeline-run run}))))

;------------------------------------------------------------------------------ Layer 1

;; The pack builder, and the readers the assertions use over it.
(defn- ^{:stratum 1} write-pack!
  "Materialize a runnable pack in a fresh temp directory. `record-count`
   records land in the source file the ingest stage reads."
  [mode record-count]
  (let [dir           (str (System/getProperty "java.io.tmpdir")
                           "/etl-cursors-test-" (random-uuid))
        pipeline-path (str dir "/pipelines/pipeline.edn")
        env-path      (str dir "/envs/local.edn")
        source-path   (str dir "/source.edn")
        out-path      (str dir "/out.edn")]
    (write-edn! pipeline-path (pipeline-edn ingest-stage-name mode))
    (write-edn! env-path (env-edn ingest-stage-name source-path out-path))
    (write-edn! source-path (rows record-count))
    {:pipeline-path pipeline-path
     :env-path      env-path
     :source-path   source-path
     :out-path      out-path}))

(defn- ^{:stratum 1} persisted-offset
  "The offset watermark on disk, or nil when no cursor was written. The
   schema name in the key falls back to the stage name because a file
   stage config declares no resource."
  [{:keys [pipeline-path]}]
  (-> (cursor-store/load-cursors nil pipeline-path)
      :cursors
      (get [ingest-stage-name ingest-stage-name])
      (get-in [:cursor :cursor/value])))

(defn- ^{:stratum 1} stage-id-keyed
  "The cursor map prime-context hands the runner, for a pipeline whose
   single stage carries `stage-id`."
  ([pipeline-path stage-id persisted]
   (stage-id-keyed pipeline-path stage-id persisted nil))
  ([pipeline-path stage-id persisted stage-config]
   (let [pipeline {:pipeline/mode   :incremental
                   :pipeline/stages [{:stage/id     stage-id
                                      :stage/name   ingest-stage-name
                                      :stage/config stage-config}]}]
     (cursor-store/save-cursors nil pipeline-path persisted)
     (-> (cursors/prime-context nil pipeline-path pipeline {})
         :context
         :pipeline-run/connector-cursors))))

(def ^{:stratum 1} ^:private one-cursor
  "One run's cursor map for the ingest stage, keyed by stage id as
   pipeline-runner reports it."
  {(random-uuid) {:stage/name          ingest-stage-name
                  :stage/schema-name   ingest-stage-name
                  :stage/connector-ref (random-uuid)
                  :cursor {:cursor/type :offset :cursor/value 7}}})

;------------------------------------------------------------------------------ Layer 2

;; ---------------------------------------------------------------------------
;; run-pack — the cross-run loop end to end
(deftest ^{:stratum 2} incremental-run-resumes-from-the-previous-runs-watermark-test
  (testing "the second run reads only what arrived after the first"
    (let [pack (write-pack! :incremental 3)
          run1 (execute-pack! pack)]
      (is (schema/succeeded? run1) (str "first run failed: " (:error run1)))
      (is (= (rows 3) (published pack)))
      (is (= 3 (persisted-offset pack)))

      ;; Two more records arrive between runs.
      (write-edn! (:source-path pack) (rows 5))
      (let [run2 (execute-pack! pack)]
        (is (schema/succeeded? run2) (str "second run failed: " (:error run2)))
        ;; The sink appends, so a re-ingest of the whole source would
        ;; show as 8 published records rather than 5.
        (is (= (rows 5) (published pack))
            "second run should publish only records 4 and 5")
        (is (= 5 (persisted-offset pack)))))))

(deftest ^{:stratum 2} incremental-run-with-no-new-records-publishes-nothing-test
  (testing "a run that finds nothing past the watermark leaves the output alone"
    (let [pack (write-pack! :incremental 3)]
      (execute-pack! pack)
      (let [run2 (execute-pack! pack)]
        (is (schema/succeeded? run2) (str "second run failed: " (:error run2)))
        (is (= (rows 3) (published pack)))
        (is (= 3 (persisted-offset pack)))))))

(deftest ^{:stratum 2} full-refresh-run-neither-writes-nor-honours-a-watermark-test
  (testing "a full-refresh pack re-reads its whole source every run"
    (let [pack (write-pack! :full-refresh 3)]
      (is (schema/succeeded? (execute-pack! pack)))
      (is (false? (.exists (cursor-file pack)))
          "full-refresh must not leave a watermark behind")
      (is (schema/succeeded? (execute-pack! pack)))
      ;; Appending sink + full re-read == every record twice.
      (is (= (into (rows 3) (rows 3)) (published pack))))))

(deftest ^{:stratum 2} unreadable-cursor-file-fails-the-run-test
  (testing "a cursor file that will not parse fails the run rather than re-ingesting"
    (let [pack (write-pack! :incremental 3)]
      (execute-pack! pack)
      (spit (cursor-file pack) "{:this is not")
      (let [result (execute-pack! pack)]
        (is (schema/failed? result))
        ;; Nothing executed, so there is no run to report on, and the
        ;; source must not have been read a second time.
        (is (nil? (:pipeline-run result)))
        (is (= (rows 3) (published pack)))))))

;; ---------------------------------------------------------------------------
;; prime-context
(deftest ^{:stratum 2} prime-context-leaves-non-incremental-pipelines-alone-test
  (testing "no cursor key is added for modes that do not carry a watermark"
    (doseq [mode [:full-refresh :backfill :reprocess nil]]
      (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
            result (cursors/prime-context nil pipeline-path
                                          {:pipeline/mode mode}
                                          {:auth {:token "t"}})]
        (is (schema/succeeded? result))
        (is (= {:auth {:token "t"}} (:context result))
            (str "mode " mode " should not be given a watermark"))))))

(deftest ^{:stratum 2} prime-context-first-run-carries-an-empty-cursor-map-test
  (testing "no cursor file yet is a success with nothing to resume from"
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          result (cursors/prime-context nil pipeline-path
                                        {:pipeline/mode :incremental} {})]
      (is (schema/succeeded? result))
      (is (= {} (:pipeline-run/connector-cursors (:context result)))))))

(deftest ^{:stratum 2} prime-context-rekeys-persisted-cursors-onto-stage-ids-test
  (testing "the persisted entry arrives under the stage id this run scheduled"
    ;; pipeline-runner looks a stage's cursor up by the :stage/id it was
    ;; scheduled with, which is minted fresh each run; the store keys by
    ;; the stage name, which is not. The translation is what makes a
    ;; loaded cursor reachable at all.
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          stage-id (random-uuid)
          keyed    (stage-id-keyed pipeline-path stage-id
                                   (persisted ingest-stage-name ingest-stage-name 3))]
      (is (= [stage-id] (keys keyed)))
      (is (= 3 (get-in keyed [stage-id :cursor :cursor/value])))))

  (testing "an entry whose schema no longer matches the stage is not applied"
    ;; Repointing a stage at a different resource leaves its old entry
    ;; in the file under the old schema. That watermark describes a
    ;; different source, so resuming from it would skip records that
    ;; were never read; the stage starts fresh instead.
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          keyed (stage-id-keyed pipeline-path (random-uuid)
                                (persisted ingest-stage-name "issues" 3)
                                {:github/resource "pulls"})]
      (is (= {} keyed))))

  (testing "an entry matching the stage's configured resource is applied"
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          stage-id (random-uuid)
          keyed    (stage-id-keyed pipeline-path stage-id
                                   (persisted ingest-stage-name "pulls" 4)
                                   {:github/resource "pulls"})]
      (is (= 4 (get-in keyed [stage-id :cursor :cursor/value])))))

  (testing "a stage name with two persisted entries takes neither by accident"
    ;; Neither schema matches the one this run will extract under, so
    ;; both are left behind rather than one being picked arbitrarily.
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          keyed (stage-id-keyed pipeline-path (random-uuid)
                                (merge (persisted ingest-stage-name "issues" 3)
                                       (persisted ingest-stage-name "merge-requests" 9)))]
      (is (= {} keyed))))

  (testing "a persisted stage that no longer exists in the pipeline is dropped"
    (let [{:keys [pipeline-path]} (write-pack! :incremental 1)
          keyed (stage-id-keyed pipeline-path (random-uuid)
                                (persisted "Removed Stage" "gone" 3))]
      (is (= {} keyed)))))

;; ---------------------------------------------------------------------------
;; persist-cursors!
(deftest ^{:stratum 2} persist-cursors-writes-only-for-a-successful-incremental-run-test
  (testing "a successful incremental run advances the watermark"
    (let [pack   (write-pack! :incremental 1)
          result (run-result :completed one-cursor)
          out    (cursors/persist-cursors! nil (:pipeline-path pack)
                                           {:pipeline/mode :incremental} result)]
      (is (= result out) "the run result passes through untouched")
      (is (= 7 (persisted-offset pack)))))

  (testing "a failed run leaves the previous watermark in place"
    ;; The stages that did complete extracted records that were never
    ;; published; advancing past them would drop them for good.
    (let [pack (write-pack! :incremental 1)]
      (cursors/persist-cursors! nil (:pipeline-path pack)
                                {:pipeline/mode :incremental}
                                (run-result :completed one-cursor))
      (let [later (assoc-in one-cursor
                            [(first (keys one-cursor)) :cursor :cursor/value] 99)
            out   (cursors/persist-cursors! nil (:pipeline-path pack)
                                            {:pipeline/mode :incremental}
                                            (run-result :failed later))]
        (is (schema/failed? out))
        (is (= 7 (persisted-offset pack))))))

  (testing "a non-incremental run writes nothing at all"
    (let [pack (write-pack! :full-refresh 1)]
      (cursors/persist-cursors! nil (:pipeline-path pack)
                                {:pipeline/mode :full-refresh}
                                (run-result :completed one-cursor))
      (is (false? (.exists (cursor-file pack)))))))
