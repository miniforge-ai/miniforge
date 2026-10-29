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
   untracked path (new in full), and every path the artifact names is
   diffed (`git diff -U0`) against its content at that base -- the
   on-disk version and the artifact's version both, so a stale artifact
   cannot hide what is on disk -- and only added lines are scanned, so
   an added copy of an existing line still counts. Each commit between
   the base and HEAD is also scanned on its own: a credential committed
   and later removed is still found, since pushing the branch publishes
   that commit.

   The base is the first of the artifact's :code/base-refs (stamped by
   the implement phase: the refs its artifact collector diffs against),
   the environment's base SHA, or HEAD that git resolves. Task work
   already committed, or restored on resume, is therefore still judged.
   Git runs where the worktree is: in the execution environment when the
   context carries :execution/execute-fn (governed runs), else on the
   host. With no worktree, every line the artifact carries is scanned; a
   supplied worktree that cannot be inspected fails the gate.

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

(defn- ^{:stratum 0} run
  "Run ARGV where CTX's worktree is and return {:exit :out}, or nil when
   it cannot be run. Inside the execution environment when CTX carries
   :execution/execute-fn, in :execution/worktree-path there; otherwise on
   the host, in that directory when present."
  [ctx argv]
  (let [dir (get ctx :execution/worktree-path)]
    (try+
      (if-let [exec! (get ctx :execution/execute-fn)]
        (let [quote #(str "'" (str/replace (str %) "'" "'\"'\"'") "'")
              r (exec! (get ctx :execution/executor) (get ctx :execution/environment-id)
                       (str/join " " (map quote argv)) {:workdir dir})]
          {:exit (get-in r [:data :exit-code] (if (:ok? r) 0 1))
           :out  (str (get-in r [:data :stdout] ""))})
        (apply shell/sh (concat argv (when dir [:dir (str dir)]))))
      (catch Exception _ nil))))

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

(defn- ^{:stratum 0} all-lines
  "Every line of TEXT, numbered from 1."
  [text]
  (map-indexed (fn [i line] [(inc i) line]) (str/split-lines (str text))))

(defn- ^{:stratum 0} findings
  "{:file :line} (and :commit, when given) for each numbered line that
   carries a secret-shaped value."
  ([path numbered-lines] (findings path numbered-lines nil))
  ([path numbered-lines commit]
   (for [[n line] numbered-lines
         :when (redaction/pattern-secret-string? line)]
     (cond-> {:file path :line n} commit (assoc :commit commit)))))

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

(defn- ^{:stratum 0} location-text
  "file:line, with the commit's short id for a line found in history."
  [{:keys [file line commit]}]
  (str (or file (msg/t :no-secrets/artifact-content)) ":" line
       (when commit (str " (" (subs commit 0 (min 12 (count commit))) ")"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-base
  "The commit the first resolvable candidate names, or nil. A range
   `A...B` (a resumed workspace) resolves to the merge base of A and B."
  [ctx candidates]
  (some (fn [ref]
          (let [[a b] (str/split ref #"\.\.\." 2)
                r (run ctx (if b
                             ["git" "merge-base" a b]
                             ["git" "rev-parse" "--verify" "--quiet" (str ref "^{commit}")]))]
            (when (zero? (get r :exit 1))
              (not-empty (str/trim (:out r))))))
        (filter #(and (string? %) (not (str/blank? %))) candidates)))

(defn- ^{:stratum 1} changed-paths
  "{:tracked [...] :untracked [...]} against BASE, or nil when git cannot
   list them."
  [ctx base]
  (let [diff      (run ctx ["git" "-c" "core.quotePath=false" "diff" "-z" "--name-only"
                            "--no-renames" base "--"])
        untracked (run ctx ["git" "-c" "core.quotePath=false" "ls-files" "-z" "--others"
                            "--exclude-standard"])]
    (when (and (zero? (get diff :exit 1)) (zero? (get untracked :exit 1)))
      {:tracked (nul-split (:out diff)) :untracked (nul-split (:out untracked))})))

(defn- ^{:stratum 1} disk-added
  "Numbered lines the worktree's PATH adds over BASE (all of an untracked
   file), or nil when they cannot be read."
  [ctx base path untracked?]
  ;; --text: a version holding a NUL byte would otherwise come back as a
  ;; hunk-less "Binary files differ" and its added lines would go unseen.
  (let [r (run ctx (if untracked?
                     ["cat" "--" path]
                     ["git" "diff" "--text" "-U0" "--no-color" "--no-ext-diff" "--no-renames"
                      base "--" path]))]
    (when (zero? (get r :exit 1))
      (if untracked? (all-lines (:out r)) (added-lines (:out r))))))

(defn- ^{:stratum 1} artifact-added
  "Numbered lines TEXT adds over PATH's content at BASE, diffed on the
   host in the owner-only TMP; every line when git cannot diff."
  [ctx base path text tmp name]
  (let [shown (when path (run ctx ["git" "show" (str base ":" path)]))
        old   (if (zero? (get shown :exit 1))
                (doto (io/file tmp (str name "-base")) (spit (:out shown)))
                (io/file "/dev/null"))
        new   (doto (io/file tmp name) (spit text))
        r     (run {} ["git" "diff" "--no-index" "--text" "-U0" "--no-color" "--no-ext-diff"
                       "--" (str old) (str new)])]
    (if (#{0 1} (:exit r)) (added-lines (:out r)) (all-lines text))))

(defn- ^{:stratum 1} history-findings
  "Findings in each commit between BASE and HEAD, scanned on its own, or
   nil when the history cannot be read."
  [ctx base]
  (let [revs (run ctx ["git" "rev-list" "--reverse" (str base "..HEAD")])]
    (when (zero? (get revs :exit 1))
      (reduce (fn [acc sha]
                (let [files (run ctx ["git" "-c" "core.quotePath=false" "diff-tree" "-z" "-r"
                                      "--no-commit-id" "--name-only" "--no-renames" sha])]
                  (if-not (zero? (get files :exit 1))
                    (reduced nil)
                    (into acc (mapcat (fn [path]
                                        (let [r (run ctx ["git" "show" "--text" "-U0" "--format="
                                                          "--no-color" "--no-ext-diff" "--no-renames"
                                                          sha "--" path])]
                                          (findings path (added-lines (:out r)) sha))))
                          (nul-split (:out files))))))
              []
              (remove str/blank? (str/split-lines (:out revs)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-no-secrets
  "Gate check (see ns docstring). Findings carry file and line (and the
   commit, for history), never the matched value: the result is logged
   and shown to the agent, so quoting the secret would copy it into both."
  [artifact ctx]
  (let [dir      (get ctx :execution/worktree-path)
        versions (artifact-versions artifact)
        base     (when dir (resolve-base ctx (base-candidates artifact ctx)))
        listed   (when base (changed-paths ctx base))
        history  (when listed (history-findings ctx base))
        scanned
        (cond
          (nil? dir)
          (vec (distinct (for [[path texts] versions, text texts, f (findings path (all-lines text))] f)))

          (nil? history) nil

          :else
          (let [tmp (scratch-dir)]
            (try
              (let [disk (concat (for [p (:tracked listed)] [p (disk-added ctx base p false)])
                                 (for [p (:untracked listed)] [p (disk-added ctx base p true)]))
                    arts (for [[i [path texts]] (map-indexed vector versions)
                               [j text] (map-indexed vector texts)]
                           [path (artifact-added ctx base path text tmp (str i "-" j))])]
                ;; A changed file whose lines could not be read leaves the
                ;; change unchecked: that is a failure, not a pass.
                (when (every? (comp some? second) disk)
                  (vec (distinct (concat (mapcat (fn [[p ls]] (findings p ls)) (concat disk arts))
                                         history)))))
              (finally
                (fs/delete-tree tmp)))))]
    (cond
      (nil? scanned)
      {:passed? false
       :errors [{:type    :worktree-uninspectable
                 :message (msg/t :no-secrets/uninspectable {:path (str dir)})}]}

      (empty? scanned)
      {:passed? true}

      :else
      {:passed? false
       :errors [{:type      :secrets-detected
                 :message   (str (msg/t :no-secrets/detected
                                        {:locations (str/join ", " (map location-text scanned))})
                                 (when (some :commit scanned)
                                   (str " " (msg/t :no-secrets/in-history))))
                 :locations scanned}]})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (added-lines "@@ -0,0 +1,2 @@\n+a\n+b\n")
  (check-no-secrets {:code/files [{:path "src/a.py"
                                   :content (str "api_key = '" "abcdefgh12" "'")}]} {})
  :leave-this-here)
