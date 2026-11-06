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
(ns ai.miniforge.gate.git-change
  "What a change does to a file, read from git: the base and diff helpers
   the :no-secrets and policy-pack gates share.

   Every git call is built by `git-argv` (--literal-pathspecs, unquoted
   paths, and for diff --no-textconv --no-ext-diff --text --no-color
   --inter-hunk-context=0) and run by `run`, where a nonzero exit or an
   executor error reads as nil. Git runs where the checkout is: through
   :execution/execute-fn in :execution/capsule-workdir for governed runs,
   else on the host in :execution/worktree-path.

   The base is the first resolvable of the artifact's :code/base-refs and
   the context's :execution/task-base-refs (both the implement phase's
   resume-aware candidates), then the environment's base SHA."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run
  "Run ARGV where CTX's checkout is and return its stdout, or nil when it
   cannot be run or exits outside OK-EXITS (default 0). Through
   :execution/execute-fn in :execution/capsule-workdir (the executor's
   default when absent) for governed runs; otherwise on the host, in
   :execution/worktree-path when present."
  ([ctx argv] (run ctx argv #{0}))
  ([ctx argv ok-exits]
   (let [r (try+
             (if-let [exec! (get ctx :execution/execute-fn)]
               (let [quote #(str "'" (str/replace (str %) "'" "'\"'\"'") "'")
                     wd    (get ctx :execution/capsule-workdir)
                     r     (exec! (get ctx :execution/executor) (get ctx :execution/environment-id)
                                  (str/join " " (map quote argv)) (cond-> {} wd (assoc :workdir wd)))]
                 {:exit (get-in r [:data :exit-code] (if (:ok? r) 0 1))
                  :out  (str (get-in r [:data :stdout] ""))})
               (let [dir (get ctx :execution/worktree-path)]
                 (apply shell/sh (concat argv (when dir [:dir (str dir)])))))
             (catch Exception _ nil))]
     (when (contains? ok-exits (:exit r))
       (str (:out r))))))

(defn ^{:stratum 0} git-argv
  "The argv for `git SUB ARGS` with the safe defaults every call gets."
  [sub & args]
  ;; --no-replace-objects: a push sends the original objects, so the scan
  ;; must not read through a local refs/replace substitute.
  (concat ["git" "--no-replace-objects" "--literal-pathspecs" "-c" "core.quotePath=false" sub]
          (when (= "diff" sub)
            ["--no-textconv" "--no-ext-diff" "--text" "--no-color" "--inter-hunk-context=0"])
          args))

(defn ^{:stratum 0} added-lines
  "[line-number text] for each line a zero-context unified diff adds,
   numbered in the new version."
  [diff]
  (loop [[line & more] (str/split-lines (str diff)) n nil acc []]
    (cond
      (nil? line) acc
      (str/starts-with? line "@@") (recur more (some-> (re-find #"\+(\d+)" line) second parse-long) acc)
      (and n (str/starts-with? line "+")) (recur more (inc n) (conj acc [n (subs line 1)]))
      ;; A context line still occupies a line of the new version.
      (and n (str/starts-with? line " ")) (recur more (inc n) acc)
      :else (recur more n acc))))

(defn ^{:stratum 0} deletion-points
  "For each hunk of a zero-context unified diff that removes lines, the
   points of the new version (after line P) where removed lines may have
   been: for a pure removal, the line it follows (0 at the start); for a
   replacement, both edges of the lines put in their place, since the hunk
   does not say where among them the removed lines were."
  [diff]
  (mapcat (fn [line]
            (when-let [[_ removed start added]
                       (re-find #"^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@" line)]
              (let [removed (if removed (parse-long removed) 1)
                    start   (parse-long start)
                    added   (if added (parse-long added) 1)]
                (cond
                  (zero? removed) []
                  (zero? added)   [start]
                  :else           [(dec start) (+ start added -1)]))))
          (str/split-lines (str diff))))

(defn ^{:stratum 0} scratch-dir
  "A fresh temp directory named from PREFIX that only its owner can enter,
   created with that mode in one step: the base and artifact copies
   written into it can hold the very credentials a gate looks for."
  [prefix]
  (fs/file (fs/create-temp-dir {:prefix prefix
                                :posix-file-permissions "rwx------"})))

(defn ^{:stratum 0} base-candidates
  "Refs to try, in order, for the commit the change is measured from. HEAD
   is not among them: once the implement phase commits, HEAD holds the
   change itself, so a caller that falls back to it says so."
  [artifact ctx]
  (concat (:code/base-refs artifact)
          (:execution/task-base-refs ctx)
          (some-> (get-in ctx [:execution/environment-metadata :base-sha]) vector)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-base
  "The commit the first resolvable candidate names, or nil. A range
   `A...B` (a resumed workspace) resolves to the merge base of A and B."
  [ctx candidates]
  (some (fn [ref]
          (let [[a b] (str/split ref #"\.\.\." 2)]
            (some-> (run ctx (if b
                               (git-argv "merge-base" a b)
                               (git-argv "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))))
                    str/trim
                    not-empty)))
        (filter #(and (string? %) (not (str/blank? %))) candidates)))

(defn ^{:stratum 1} version-diff
  "The zero-context diff of TEXT, the artifact's version of PATH, against
   PATH's blob at BASE (against nothing when there is no base blob), or
   nil when git cannot diff them. Both versions are written under DIR as
   NAME and NAME-base and diffed on the host."
  [ctx base path text dir name]
  (let [shown (when (and base path) (run ctx (git-argv "cat-file" "blob" (str base ":" path))))
        old   (if shown (doto (io/file dir (str name "-base")) (spit shown)) (io/file "/dev/null"))
        new   (doto (io/file dir name) (spit text))]
    (run {} (git-argv "diff" "--no-index" "-U0" "--" (str old) (str new)) #{0 1})))
