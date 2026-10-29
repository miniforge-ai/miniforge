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
(ns ai.miniforge.cli.workflow-runner.spec-kanban
  "Work-spec kanban lifecycle: specs that live under `work/` move
   through in-progress/done/failed folders as their workflow runs.
   Split out of `ai.miniforge.cli.workflow-runner` (rule 210: the
   parent namespace measured 10 real layers, max 3; each concern moves
   to its own layer-coherent file)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private work-dirs
  "Kanban folder structure under work/."
  {:in-progress "work/in-progress"
   :done        "work/done"
   :failed      "work/failed"})

(defn- ^{:stratum 0} work-spec?
  "True when the spec provenance points to a file under work/."
  [provenance]
  (when-let [source (:source-file provenance)]
    (str/starts-with? (str source) "work/")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} move-spec!
  "Move a work spec file to the target kanban folder.
   No-op if the spec isn't under work/ or the file doesn't exist."
  [provenance target-key]
  (when (work-spec? provenance)
    (let [source (str (:source-file provenance))
          target-dir (get work-dirs target-key)]
      (when (and target-dir (fs/exists? source))
        (fs/create-dirs target-dir)
        (let [target (str target-dir "/" (fs/file-name source))]
          (fs/move source target {:replace-existing true})
          target)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} move-spec-to-in-progress!
  "Move a work spec to in-progress when execution starts.
   Returns updated provenance with new source-file path,
   or the original provenance if the move was a no-op."
  [provenance]
  (if-let [new-path (move-spec! provenance :in-progress)]
    (assoc provenance :source-file new-path)
    provenance))

(defn ^{:stratum 2} move-spec-on-completion!
  "Move a work spec to done or failed based on workflow result."
  [provenance result]
  (if (phase/succeeded? result)
    (move-spec! provenance :done)
    (move-spec! provenance :failed)))

(defn ^{:stratum 2} start-spec!
  "`move-spec-to-in-progress!` for a run's input, keeping its spec
   traceable. Takes the spec file's digest before the move and returns
   `[provenance workflow-input]`: the provenance updated as
   `move-spec-to-in-progress!` does, and the input carrying the file's
   absolute location after the move (`:spec/location`) and that digest
   (`:spec/hash`). The run's checkpoint manifest records both, so resume
   can check the spec. `:spec/path` stays as given: PR provenance uses it."
  [workflow-input provenance]
  (let [spec-path (:spec/path workflow-input)
        spec-hash (some-> spec-path workflow/spec-file-hash)
        new-path (move-spec! provenance :in-progress)
        location (or new-path spec-path)]
    [(cond-> provenance new-path (assoc :source-file new-path))
     (cond-> workflow-input
       location (assoc :spec/location (str (fs/absolutize location)))
       spec-hash (assoc :spec/hash spec-hash))]))

(defn ^{:stratum 2} finish-spec!
  "`move-spec-on-completion!` that also points run `workflow-id`'s
   checkpoint manifest at the spec's new place (work/done or work/failed),
   so a later resume still finds it. Returns the new path, nil when nothing
   moved."
  [workflow-id provenance result]
  (when-let [new-path (move-spec! provenance (if (phase/succeeded? result) :done :failed))]
    (workflow/record-spec-path! (str workflow-id) (str (fs/absolutize new-path)))
    new-path))
