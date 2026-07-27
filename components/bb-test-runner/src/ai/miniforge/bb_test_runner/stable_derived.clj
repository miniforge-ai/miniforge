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
(ns ai.miniforge.bb-test-runner.stable-derived
  "Pure helpers for the stable-derived Polylith test wrapper: parsing
   and rendering project selectors, detecting stable tags, building the
   Polylith `ws` changed-projects queries, sanitizing the git worktree
   env before spawning `poly test`, deriving the heartbeat interval, and
   ordering/grouping a project vector for diagnostic runs (additive
   expand, breadth-first bisect). Two otherwise-unconnected concerns
   share this namespace rather than each getting a two-def file of
   their own — see `diagnostic-plan` for the CLI-arg-parsing and
   plan-assembly orchestration built on top of this.

   Layer 0: independent leaves — selector parsing/formatting, stable-tag
   detection and glob constants, the changed-projects argv constant,
   error-map construction, git-worktree-key/heartbeat-default constants,
   the expand-start-size default, `shuffle-projects`, and
   `bisect-project-groups`.
   Layer 1: `stable-tag-globs`, `changed-projects-command`,
   `parse-project-list-output`, `sanitize-git-worktree-env`,
   `heartbeat-seconds`, `order-projects`, `positive-start-size` — each
   built on exactly one Layer 0 def.
   Layer 2: `changed-projects-since-stable-command` and
   `expand-project-groups`, each built on a Layer 1 def."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import
   [clojure.lang PersistentQueue]
   [java.lang Exception Long NumberFormatException]
   [java.util ArrayList Collection Collections Random]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stable-tag-glob-patterns
  "Supported stable-tag glob patterns.
   The repo has historical `stable-*` tags and a few older `stable/*`
   variants, so the wrapper treats both as stable baselines."
  ["stable-*" "stable/*"])

(def ^{:stratum 0} ^:private default-heartbeat-seconds
  30)

(def ^{:stratum 0} ^:private default-expand-start-size
  1)

(def ^{:stratum 0} ^:private git-worktree-env-keys
  ["GIT_INDEX_FILE" "GIT_DIR" "GIT_WORK_TREE" "GIT_COMMON_DIR"])

(def ^{:stratum 0} ^:private changed-or-affected-projects-argv
  ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
   "skip:dev" "color-mode:none"])

(defn ^{:stratum 0} stable-tags-present?
  "True when `tags` contains at least one recognized stable tag."
  [tags]
  (boolean
   (some (fn [tag]
           (let [normalized (some-> tag str str/trim not-empty)]
             (and normalized
                  (or (str/starts-with? normalized "stable-")
                      (str/starts-with? normalized "stable/")))))
         tags)))

(defn ^{:stratum 0} parse-project-selector
  "Parse a Polylith project selector or env value into a vector of
   project names.

   Accepted forms:
   - `project:proj1:proj2`
   - `proj1:proj2`
   - `proj1,proj2`"
  [selector]
  (let [raw (some-> selector str/trim not-empty)
        value (cond
                (nil? raw) nil
                (str/starts-with? raw "project:")
                (subs raw (count "project:"))
                :else raw)]
    (->> (some-> value (str/split #"[,:]"))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn ^{:stratum 0} format-project-selector
  "Render an explicit Polylith project selector argument from project
   names."
  [projects]
  (when (seq projects)
    (str "project:" (str/join ":" projects))))

(defn ^{:stratum 0} parse-error
  "Build a `{:ok? false :error {...}}` result map. Shared by every
   parser in this component — also used by `diagnostic-plan`'s
   `parse-long-arg`, cross-namespace."
  [code message data]
  {:ok? false
   :error {:code code
           :message message
           :data data}})

(defn- ^{:stratum 0} shuffle-projects
  [projects seed]
  (let [alist (ArrayList. ^Collection projects)]
    (Collections/shuffle alist (Random. (long (or seed 0))))
    (vec alist)))

(defn ^{:stratum 0} bisect-project-groups
  "Return contiguous project groups in breadth-first binary partition
   order."
  [projects]
  (let [root (vec projects)]
    (loop [queue (cond-> PersistentQueue/EMPTY
                   (> (count root) 1) (conj root))
           groups []]
      (if (empty? queue)
        groups
        (let [group (peek queue)
              queue-tail (pop queue)
              mid (quot (count group) 2)
              left (subvec group 0 mid)
              right (subvec group mid)
              next-groups (cond-> groups
                            (seq left) (conj left)
                            (seq right) (conj right))
              next-queue (cond-> queue-tail
                           (> (count left) 1) (conj left)
                           (> (count right) 1) (conj right))]
          (recur next-queue next-groups))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stable-tag-globs
  "Return the stable-tag glob patterns recognized by Miniforge's
   stable-derived test scope."
  []
  stable-tag-glob-patterns)

(defn ^{:stratum 1} changed-projects-command
  "Return the argv that queries Polylith for the current changed-or-
   affected project set."
  []
  changed-or-affected-projects-argv)

(defn ^{:stratum 1} parse-project-list-output
  "Parse a Polylith `ws get:changes:changed-or-affected-projects`
   response into a vector of project names.

   Returns a vector on valid blank/sequential/set output, or
   `{:ok? false :error ...}` when the output is invalid or unparseable."
  [output]
  (let [trimmed (some-> output str/trim not-empty)]
    (if-not trimmed
      []
      (try
        (let [parsed (edn/read-string trimmed)]
          (cond
            (sequential? parsed) (mapv str parsed)
            (set? parsed) (->> parsed (map str) sort vec)
            :else (parse-error :bb-test-runner/invalid-project-list
                               "Expected a sequential or set project list."
                               {:output output
                                :parsed parsed})))
        (catch Exception e
          (parse-error :bb-test-runner/invalid-project-list
                       "Failed to parse project list output."
                       {:output output
                        :cause (.getMessage e)}))))))

(defn ^{:stratum 1} sanitize-git-worktree-env
  "Remove git worktree/index variables that must not leak into child
   processes.

   `git commit` and related flows can export worktree-specific git vars.
   If those leak into nested `git` calls inside tests, temp repos and
   temp worktrees stop behaving like standalone repos. The stable-derived
   wrapper must strip them before spawning `poly test`."
  [env]
  (apply dissoc env git-worktree-env-keys))

(defn ^{:stratum 1} heartbeat-seconds
  "Return the heartbeat interval, defaulting to 30 seconds.
   Invalid, missing, or non-positive values fall back to the default."
  [env]
  (let [raw (some-> (get env "MINIFORGE_TEST_HEARTBEAT_SECONDS")
                    str/trim
                    not-empty)]
    (if-not raw
      default-heartbeat-seconds
      (let [parsed (try
                     (Long/parseLong raw)
                     (catch NumberFormatException _
                       nil))]
        (if (pos-int? parsed)
          parsed
          default-heartbeat-seconds)))))

(defn ^{:stratum 1} order-projects
  "Apply diagnostic ordering controls to a project vector."
  [projects {:keys [direction order seed]}]
  (let [ordered (case order
                  :random (shuffle-projects projects seed)
                  (vec projects))]
    (case direction
      :back (vec (reverse ordered))
      ordered)))

(defn- ^{:stratum 1} positive-start-size
  [project-count requested-size]
  (-> (or requested-size default-expand-start-size)
      (max default-expand-start-size)
      (min project-count)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} changed-projects-since-stable-command
  "Return the argv that queries Polylith for changed-or-affected
   projects since the current stable tag anchor."
  []
  (let [argv (vec (changed-projects-command))
        marker "get:changes:changed-or-affected-projects"
        marker-index (.indexOf argv marker)]
    (if (neg? marker-index)
      (throw (ex-info "Invariant failed: changed-projects command is missing the Polylith change marker."
                      {:argv argv
                       :marker marker}))
      (let [insert-index (inc marker-index)]
        (vec (concat (subvec argv 0 insert-index)
                     ["since:stable"]
                     (subvec argv insert-index)))))))

(defn ^{:stratum 2} expand-project-groups
  "Return additive project groups that double in size until they cover
   the full ordered project set."
  [projects start-size]
  (let [project-vec (vec projects)
        project-count (count project-vec)]
    (if (zero? project-count)
      []
      (loop [group-size (positive-start-size project-count start-size)
             groups []]
        (let [group (subvec project-vec 0 group-size)
              next-groups (conj groups group)]
          (if (= group-size project-count)
            next-groups
            (recur (min project-count (* 2 group-size))
                   next-groups)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (parse-project-selector "project:miniforge:miniforge-core")
  (stable-tags-present? ["stable-20260506"])
  (order-projects ["a" "b" "c"] {:direction :back})
  (expand-project-groups ["a" "b" "c" "d" "e"] 1)
  (bisect-project-groups ["a" "b" "c" "d"])

  :leave-this-here)
