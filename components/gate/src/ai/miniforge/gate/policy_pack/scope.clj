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
(ns ai.miniforge.gate.policy-pack.scope
  "Scopes LLM-judge findings to the change: a finding inside the top-level
   forms the change edits keeps its rule's enforcement; one elsewhere in a
   touched file predates the change and becomes a non-blocking warning.

   Each finding names its file under `:file` and its line under `:line`, as
   `semantic-analyzer` reports them. A finding the gate cannot place blocks
   as before: one with no positive line, or in a file whose edits cannot be
   found because there is no checkout, no base resolves, git fails, or the
   content does not parse."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.gate.edited-forms :as edited-forms]
   [ai.miniforge.gate.git-change :as git-change]
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.policy-pack.artifact :as artifact]
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private scratch-prefix
  "Name prefix of the temp directory holding the base and changed versions
   the gate diffs, so one left behind is recognisable as its own."
  "mf-policy-pack-")

(defn- ^{:stratum 0} judge-entry?
  "True for a violation entry from the LLM judge, whose per-site findings
   sit under `[:violation :violations]`."
  [entry]
  (= :semantic (get-in entry [:violation :type])))

(defn- ^{:stratum 0} outside-change?
  "True when judge FINDING has a line of its file that the file's edited
   lines in SCOPE leave out. A finding in a file SCOPE does not cover, or on
   no line of the file (none, 0, or past the last), cannot be placed and is
   not outside."
  [scope finding]
  (let [{:keys [edited line-count]} (get scope (:file finding))
        line (:line finding)]
    (boolean (and edited (pos-int? line) (<= line line-count)
                  (not (contains? edited line))))))

(defn- ^{:stratum 0} outside-change-message
  "The warning message for VIOLATION's FINDINGS outside the change."
  [violation findings]
  (msg/t :policy-pack/outside-change
         {:message (:message violation)
          :sites   (str/join ", " (map #(str (:file %) ":" (:line %)) findings))}))

(defn- ^{:stratum 0} file-scope
  "{:edited lines :line-count n} for changed FILE ({:path :content}): the
   lines the change edits, diffed against BASE under DIR as NAME, and how
   many lines the file has. Nil when the edits cannot be placed."
  [ctx base dir name {:keys [path content]}]
  (when (and (string? path) (string? content))
    (when-let [diff (git-change/version-diff ctx base path content dir name)]
      (let [lines (edited-forms/edited-lines path content
                                             (set (map first (git-change/added-lines diff)))
                                             (git-change/removals diff))]
        (when-not (anomaly/anomaly? lines)
          {:edited lines :line-count (count (str/split-lines content))})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} change-scope
  "{path file-scope} (see `file-scope`) for each of ARTIFACT's changed
   files whose edits can be placed against the task base. A file left out
   is judged whole: so is every file when there is no checkout or no base
   resolves."
  [artifact ctx]
  (let [base (when (or (get ctx :execution/execute-fn) (get ctx :execution/worktree-path))
               (git-change/resolve-base ctx (git-change/base-candidates artifact ctx)))]
    (if-not base
      {}
      (let [dir (git-change/scratch-dir scratch-prefix)]
        (try
          (into {}
                (keep-indexed (fn [i file]
                                (when-let [placed (file-scope ctx base dir (str i) file)]
                                  [(:path file) placed])))
                (artifact/artifact-changed-files artifact))
          (finally (fs/delete-tree dir)))))))

(defn- ^{:stratum 1} scope-entry
  "Judge violation ENTRY split by SCOPE: findings outside the change move to
   a :warn copy of the entry whose message says so; the rest stay in ENTRY.
   The blocking part comes last because `checks/classify-rules` keeps a
   rule's last entry as its evidence summary."
  [scope {:keys [violation] :as entry}]
  (let [{outside true inside false} (group-by #(outside-change? scope %)
                                              (:violations violation))]
    (if (empty? outside)
      [entry]
      (cond-> [(-> entry
                   (assoc-in [:rule :rule/enforcement :action] :warn)
                   (assoc :violation (assoc violation
                                            :violations (vec outside)
                                            :message (outside-change-message violation outside))))]
        (seq inside) (conj (assoc-in entry [:violation :violations] (vec inside)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scope-violations
  "VIOLATIONS, compiled-rule entries for ARTIFACT, with each judge finding
   outside the change's edited lines moved to a non-blocking :warn entry.
   Diffs the change only when the judge found something."
  [artifact ctx violations]
  (if (some judge-entry? violations)
    (let [scope (change-scope artifact ctx)]
      (vec (mapcat #(if (judge-entry? %) (scope-entry scope %) [%]) violations)))
    violations))
