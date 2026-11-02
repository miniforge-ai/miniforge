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
(ns ai.miniforge.deliberation-lab.lab
  "Run, verify and hash development experiments. A run creates a new
   directory holding `journal/`, a hash-chained journal of record files
   only, and the derived `report.md`. The journal holds an opened record pinning the manifest, prompt,
   tasks and implementation hashes, every turn, every cell result and a
   closing report record; G0 is never evaluated."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.interface :as harness]
   [ai.miniforge.deliberation-lab.adapter :as adapter]
   [ai.miniforge.deliberation-lab.manifest :as manifest]
   [ai.miniforge.deliberation-lab.messages :as messages]
   [ai.miniforge.deliberation-workspace.interface :as board]
   [ai.miniforge.file-durability.interface :as durable]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} protocol-version
  "Pinned into every opened record so a reader can tell which lab protocol
   wrote the journal."
  "deliberation-lab/1")

(def ^{:stratum 0} report-file
  "The derived Markdown report, beside the journal and never inside it."
  "report.md")

(def ^{:stratum 0} journal-directory
  "The run subdirectory that holds only journal records, which the
   journal reader requires."
  "journal")

(def ^{:stratum 0} mock-usage
  "Fixed metering for offline runs; mock spend has no evidential value."
  {:input 100 :output 20 :provider-usd 0})

(def ^{:stratum 0} implementation-resources
  "Source files whose content is pinned into every run's opened record."
  (concat
   (map #(str "ai/miniforge/deliberation_lab/" % ".clj") ["adapter" "lab" "manifest" "main"])
   (map #(str "ai/miniforge/deliberation_harness/" % ".clj")
        ["codec" "experiment" "fixtures" "journal" "report" "turn"])
   (map #(str "ai/miniforge/deliberation_workspace/" % ".clj")
        ["admission" "commit" "conflict" "guards" "object" "projection" "provenance"
         "run" "scheduler" "semantics" "termination" "transaction" "validation"])))

(defn ^{:stratum 0} implementation-hash []
  (harness/digest (into (sorted-map)
                        (map (fn [path] [path (some-> (io/resource path) slurp)]))
                        implementation-resources)))

(defn ^{:stratum 0} mock-call
  "An offline `call` that answers every request with the task's reference
   solution. It exercises plumbing only."
  [tasks]
  (let [counter (atom 0)
        by-id (into {} (map (juxt :id identity)) tasks)]
    (fn [request]
      (let [n (swap! counter inc)]
        {:payload {:operations [{:op :assert-claim
                                 :creates [{:id (str "mock-" n) :type :claim
                                            :statement "SYNTHETIC offline plumbing contribution"}]}]
                   :inspect (:available-experiments request)
                   :solution (:reference (by-id (get-in request [:task :id])))
                   :rationale "SYNTHETIC mock reference; not model evidence"}
         :usage mock-usage}))))

(defn- ^{:stratum 0} refuse [type message-key data]
  (anomaly/anomaly type (messages/system-t message-key) data))

(defn- ^{:stratum 0} journal-path [run-directory]
  (io/file run-directory journal-directory))

(defn- ^{:stratum 0} cells
  "Every (task, condition, tier, replicate) in manifest order."
  [manifest tasks]
  (for [task tasks
        condition (:conditions manifest)
        tier (:tiers manifest)
        replicate (range (:replicates manifest))]
    [task condition tier replicate]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} open-run!
  "Create the new run directory `output` and start its journal, or return
   the anomaly when `output` already exists or the journal cannot start."
  [output]
  (if (.mkdir (io/file output))
    (harness/open-journal! (journal-path output))
    (refuse :conflict :anomaly/output-exists {:directory (str output)})))

(defn- ^{:stratum 1} pin
  "`manifest` with the hashes of everything the run depends on."
  [manifest tasks]
  (assoc manifest
         :prompt-hash (harness/digest (adapter/system-prompt))
         :task-hash (harness/digest tasks)
         :implementation-hash (implementation-hash)
         :protocol-version protocol-version))

(defn- ^{:stratum 1} preflight
  "The pinned manifest, or the anomaly that refuses the run before any call.
   Each check runs only once the ones before it pass."
  [manifest tasks]
  (cond
    (anomaly/anomaly? tasks) tasks
    (seq (manifest/defects manifest))
    (refuse :invalid-input :anomaly/invalid-manifest {:defects (manifest/defects manifest)})
    (seq (harness/fixture-defects tasks))
    (refuse :fault :anomaly/invalid-fixtures {:defects (vec (harness/fixture-defects tasks))})
    :else (pin manifest tasks)))

(defn- ^{:stratum 1} run-cells
  "Run every cell, stopping at the first journal anomaly."
  [pinned tasks call record!]
  (reduce (fn [results [task condition tier replicate]]
            (let [result (harness/run-cell pinned task condition tier replicate call record!)]
              (if (anomaly/anomaly? result) (reduced result) (conj results result))))
          []
          (cells pinned tasks)))

(defn- ^{:stratum 1} replays?
  "True when the board recorded for an activation rebuilds from its log. A
   log that cannot be replayed at all, such as one naming an absent object,
   does not rebuild. Plain `try`: this base has no slingshot dependency and
   would add one only for its leaf catches (dewey 211, exemption 1)."
  [tasks-by-id {:keys [task workspace]}]
  (try
    (= (:workspace/objects workspace)
       (:workspace/objects (reduce board/replay-transaction
                                   (harness/initial-workspace (tasks-by-id task))
                                   (:workspace/log workspace))))
    (catch Exception _ false)))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} record-run!
  "Journal the opened record, every cell and the closing report record,
   then write the derived report. Returns the run summary or the first
   journal anomaly."
  [pinned tasks journal output call]
  (let [record! #(harness/append! journal %)
        opened (record! {:kind :experiment-opened :manifest pinned :tasks tasks})
        results (if (anomaly/anomaly? opened) opened (run-cells pinned tasks call record!))
        report (when-not (anomaly/anomaly? results) (harness/render-report pinned results))
        closed (when report
                 (record! {:kind :experiment-completed :manifest-hash (harness/digest pinned)
                           :results results :report report :gate {:G0 :not-evaluated}}))]
    (cond
      (anomaly/anomaly? results) results
      (anomaly/anomaly? closed) closed
      :else (let [written (durable/write-new-text! (io/file output report-file) report)]
              (if (anomaly/anomaly? written)
                written
                {:cells (count results) :journal output :G0 :not-evaluated})))))

(defn ^{:stratum 2} execute!
  "Run `manifest` into the new directory `output` with the call `make-call`
   builds from the validated manifest, so nothing is constructed for a
   refused one. Returns a run summary, or the anomaly that refused or
   stopped the run."
  [manifest output make-call]
  (let [tasks (harness/tasks)
        pinned (preflight manifest tasks)
        journal (when-not (anomaly/anomaly? pinned) (open-run! output))]
    (cond
      (anomaly/anomaly? pinned) pinned
      (anomaly/anomaly? journal) journal
      :else (record-run! pinned tasks journal output (make-call pinned)))))

(defn- ^{:stratum 1} task-package?
  "True when `tasks` is a sequence of task maps, as an opened record holds."
  [tasks]
  (and (sequential? tasks) (every? map? tasks)))

(defn ^{:stratum 2} verify!
  "Verify the journal of the run in `directory`: one unbroken chain that opens and
   closes a run, with every recorded board rebuilt from its own log."
  [directory]
  (let [records (harness/read-journal (journal-path directory))
        payloads (when-not (anomaly/anomaly? records) (map :payload records))
        tasks (:tasks (first payloads))
        tasks-by-id (when (task-package? tasks) (into {} (map (juxt :id identity)) tasks))
        activations (filter #(= :activation (:kind %)) payloads)]
    (cond
      (anomaly/anomaly? records) records
      (not (and (= :experiment-opened (:kind (first payloads)))
                (= :experiment-completed (:kind (last payloads)))
                (task-package? tasks)))
      (refuse :conflict :anomaly/incomplete-run {:directory (str directory)})
      (not-every? #(replays? tasks-by-id %) activations)
      (refuse :conflict :anomaly/replay-mismatch {:directory (str directory)})
      :else {:records (count records) :replayed-activations (count activations)
             :verified? true})))
