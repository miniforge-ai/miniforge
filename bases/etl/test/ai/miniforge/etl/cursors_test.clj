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
