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
(ns ai.miniforge.gate.no-secrets
  "The :no-secrets gate: fail when a change adds a secret-shaped line.

   What a change added comes from git, not from comparing line sets.
   Every path git reports changed against the task's base commit, every
   untracked path, and every path the artifact names is diffed
   (`git diff --no-index -U0`) against its content at that base -- the
   on-disk version and the artifact's version both, so a stale artifact
   cannot hide what is on disk -- and only added lines are scanned, so
   an added copy of an existing line still counts.

   The base is the first of the artifact's :code/base-refs (stamped by
   the implement phase: the refs its artifact collector diffs against),
   the environment's base SHA, or HEAD that git resolves. Task work
   already committed, or restored on resume, is therefore still judged.
   With no worktree or no git, every line the artifact carries is
   scanned.

   Detection is redaction/pattern-secret-string?: the shared secret
   patterns, without the payment-card checksum that ordinary numbers in
   code pass."
  (:require [ai.miniforge.gate.messages :as msg]
            [ai.miniforge.redaction.interface :as redaction]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git
  "Run git ARGS, in DIR when given, with paths never quoted. Returns
   {:exit :out}, :out decoded as OUT-ENC (\"UTF-8\" or :bytes), or nil
   when git cannot be run."
  [dir out-enc & args]
  (try+
    (apply shell/sh (concat ["git"] (when dir ["-C" (str dir)])
                            ["-c" "core.quotePath=false"] args [:out-enc out-enc]))
    (catch Exception _ nil)))

(defn- ^{:stratum 0} nul-split
  "NUL-terminated git output as paths, untrimmed: a name may start or end
   with a space."
  [s]
  (into [] (remove empty?) (str/split (str s) #"\u0000")))

(defn ^{:stratum 0} added-lines
  "[line-number text] for each line a zero-context unified diff adds,
   numbered in the new version. Public for tests."
  [diff]
  (loop [[line & more] (str/split-lines (str diff)) n nil acc []]
    (cond
      (nil? line) acc
      (str/starts-with? line "@@") (recur more (some-> (re-find #"\+(\d+)" line) second parse-long) acc)
      (and n (str/starts-with? line "+")) (recur more (inc n) (conj acc [n (subs line 1)]))
      :else (recur more n acc))))

(defn ^{:stratum 0} scratch-dir
  "A fresh temp directory only its owner can enter, created with that
   mode in one step: the base and artifact copies written into it can
   hold the very credentials being looked for. Public for tests."
  []
  (fs/file (fs/create-temp-dir {:prefix "mf-no-secrets-"
                                :posix-file-permissions "rwx------"})))

(defn- ^{:stratum 0} findings
  "{:file :line} for each numbered line that carries a secret-shaped value."
  [path numbered-lines]
  (for [[n line] numbered-lines
        :when (redaction/pattern-secret-string? line)]
    {:file path :line n}))

(defn- ^{:stratum 0} base-candidates
  "Refs to try, in order, for the commit the change is measured from."
  [artifact ctx]
  (concat (:code/base-refs artifact)
          (some-> (get-in ctx [:execution/environment-metadata :base-sha]) vector)
          ["HEAD"]))

(defn- ^{:stratum 0} artifact-versions
  "{path [content ...]} for what the artifact itself carries: its
   :code/files entries, and :content / :artifact/content under
   :artifact/path (nil when absent)."
  [artifact]
  (let [content (or (:content artifact) (:artifact/content artifact))]
    (cond-> (reduce (fn [m {:keys [path content]}]
                      (cond-> m (string? content) (update path (fnil conj []) content)))
                    {} (:code/files artifact))
      (some? content) (update (:artifact/path artifact) (fnil conj [])
                              (if (string? content) content (pr-str content))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-base
  "The commit the first resolvable candidate names, or nil. A range
   `A...B` (a resumed workspace) resolves to the merge base of A and B."
  [dir candidates]
  (some (fn [ref]
          (let [[a b] (str/split ref #"\.\.\." 2)
                r (if b
                    (git dir "UTF-8" "merge-base" a b)
                    (git dir "UTF-8" "rev-parse" "--verify" "--quiet" (str ref "^{commit}")))]
            (when (zero? (get r :exit 1))
              (not-empty (str/trim (:out r))))))
        (filter #(and (string? %) (not (str/blank? %))) candidates)))

(defn- ^{:stratum 1} changed-paths
  "Paths changed against BASE (committed, staged or not) and untracked
   paths, or nil when git cannot list them."
  [dir base]
  (let [diff      (git dir "UTF-8" "diff" "-z" "--name-only" "--no-renames" base "--")
        untracked (git dir "UTF-8" "ls-files" "-z" "--others" "--exclude-standard")]
    (when (and (zero? (get diff :exit 1)) (zero? (get untracked :exit 1)))
      (into (nul-split (:out diff)) (nul-split (:out untracked))))))

(defn- ^{:stratum 1} base-file
  "A file holding PATH's content at BASE (written under TMP as NAME), or
   /dev/null when PATH did not exist there."
  [dir base path tmp name]
  (let [r (when path (git dir :bytes "show" (str base ":" path)))]
    (if (zero? (get r :exit 1))
      (let [f (io/file tmp name)]
        (with-open [out (io/output-stream f)] (.write out ^bytes (:out r)))
        f)
      (io/file "/dev/null"))))

(defn- ^{:stratum 1} added-against
  "Numbered lines NEW adds over OLD (files), or nil when git cannot diff."
  [old new]
  ;; --text: a version holding a NUL byte would otherwise come back as a
  ;; hunk-less "Binary files differ" and its added lines would go unseen.
  (let [r (git nil "UTF-8" "diff" "--no-index" "--text" "--no-color" "--no-ext-diff" "-U0" "--"
               (str old) (str new))]
    (when (#{0 1} (:exit r))
      (added-lines (:out r)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-no-secrets
  "Gate check (see ns docstring). Findings carry file and line, never the
   matched value: the result is logged and shown to the agent, so
   quoting the secret would copy it into both."
  [artifact ctx]
  (let [dir      (get ctx :execution/worktree-path)
        base     (when dir (resolve-base dir (base-candidates artifact ctx)))
        versions (artifact-versions artifact)
        on-disk  (when base (changed-paths dir base))
        locations
        (if-not on-disk
          (into [] (mapcat (fn [[path texts]]
                             (mapcat #(findings path (map-indexed (fn [i l] [(inc i) l])
                                                                  (str/split-lines %)))
                                     texts)))
                versions)
          (let [tmp (scratch-dir)]
            (try
              (into []
                    (comp (map-indexed
                           (fn [i path]
                             (let [old  (base-file dir base path tmp (str i "-base"))
                                   disk (when path (io/file dir path))
                                   news (concat (when (and disk (.isFile disk)) [disk])
                                                (map-indexed (fn [j text]
                                                               (doto (io/file tmp (str i "-" j))
                                                                 (spit text)))
                                                             (get versions path)))]
                               ;; A diff git could not produce scans the whole
                               ;; version: fail closed, never open.
                               (mapcat #(findings path (or (added-against old %)
                                                           (map-indexed (fn [n l] [(inc n) l])
                                                                        (str/split-lines (slurp %)))))
                                       news))))
                          cat
                          (distinct))
                    (distinct (concat on-disk (keys versions))))
              (finally
                (fs/delete-tree tmp)))))]
    (if (empty? locations)
      {:passed? true}
      {:passed? false
       :errors [{:type      :secrets-detected
                 :message   (msg/t :no-secrets/detected
                                   {:locations (str/join ", "
                                                         (map (fn [{:keys [file line]}]
                                                                (str (or file (msg/t :no-secrets/artifact-content))
                                                                     ":" line))
                                                              locations))})
                 :locations locations}]})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (added-lines "@@ -0,0 +1,2 @@\n+a\n+b\n")
  (check-no-secrets {:code/files [{:path "src/a.py"
                                   :content (str "api_key = '" "abcdefgh12" "'")}]} {})
  :leave-this-here)
