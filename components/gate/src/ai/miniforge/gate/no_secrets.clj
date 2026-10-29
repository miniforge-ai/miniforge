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
   an added copy of an existing line still counts. Each commit between the base and HEAD is also scanned on its own,
   against its first parent so a merge's own edits count: a credential
   committed and later removed is still found, since pushing the branch
   publishes that commit.

   Every git call goes through `git-argv`, which always adds
   --literal-pathspecs and, for diff and show, --no-textconv
   --no-ext-diff --text: a textconv or external diff driver could mask
   content, pathspec magic could select the wrong file, and a NUL byte
   would hide a file's lines behind \"Binary files differ\". Every
   command goes through `run`, where a nonzero exit or an executor error
   is nil, which the check reports as uninspectable.

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
  "Run ARGV where CTX's worktree is and return its stdout, or nil when it
   cannot be run or exits outside OK-EXITS (default 0). Inside the
   execution environment when CTX carries :execution/execute-fn, in
   :execution/worktree-path there; otherwise on the host, in that
   directory when present."
  ([ctx argv] (run ctx argv #{0}))
  ([ctx argv ok-exits]
   (let [dir (get ctx :execution/worktree-path)
         r   (try+
               (if-let [exec! (get ctx :execution/execute-fn)]
                 (let [quote #(str "'" (str/replace (str %) "'" "'\"'\"'") "'")
                       r (exec! (get ctx :execution/executor) (get ctx :execution/environment-id)
                                (str/join " " (map quote argv)) {:workdir dir})]
                   {:exit (get-in r [:data :exit-code] (if (:ok? r) 0 1))
                    :out  (str (get-in r [:data :stdout] ""))})
                 (apply shell/sh (concat argv (when dir [:dir (str dir)]))))
               (catch Exception _ nil))]
     (when (contains? ok-exits (:exit r))
       (str (:out r))))))

(defn- ^{:stratum 0} git-argv
  "The argv for `git SUB ARGS` with the safe defaults every call gets:
   --literal-pathspecs, unquoted paths, and for diff and show
   --no-textconv --no-ext-diff --text --no-color."
  [sub & args]
  (concat ["git" "--literal-pathspecs" "-c" "core.quotePath=false" sub]
          (when (#{"diff" "show"} sub) ["--no-textconv" "--no-ext-diff" "--text" "--no-color"])
          args))

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

(defn- ^{:stratum 0} every-line
  "{:content TEXT :added <every line number>}: a version that is new in full."
  [text]
  {:content (str text)
   :added   (set (range 1 (inc (count (str/split-lines (str text))))))})

(defn- ^{:stratum 0} findings
  "{:file :line} (and :commit, when given) for each ADDED line number of
   CONTENT that carries a secret-shaped value."
  ([path version] (findings path version nil))
  ([path {:keys [content added]} commit]
   (let [lines (vec (str/split-lines content))]
     (for [n (sort added)
           :when (redaction/pattern-secret-string? (get lines (dec n)))]
       (cond-> {:file path :line n} commit (assoc :commit commit))))))

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
          (let [[a b] (str/split ref #"\.\.\." 2)]
            (some-> (run ctx (if b
                               (git-argv "merge-base" a b)
                               (git-argv "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))))
                    str/trim
                    not-empty)))
        (filter #(and (string? %) (not (str/blank? %))) candidates)))

(defn- ^{:stratum 1} changed-paths
  "{:tracked [...] :untracked [...]} against BASE, or nil when git cannot
   list them."
  [ctx base]
  (let [diff      (run ctx (git-argv "diff" "-z" "--name-only" "--no-renames" base "--"))
        untracked (run ctx (git-argv "ls-files" "-z" "--others" "--exclude-standard"))]
    (when (and diff untracked)
      {:tracked (nul-split diff) :untracked (nul-split untracked)})))

(defn- ^{:stratum 1} disk-version
  "{:content :added} for the worktree's PATH against BASE (an untracked
   file is new in full), or nil when it cannot be read."
  [ctx base path untracked?]
  (if untracked?
    (some-> (run ctx ["cat" "--" path]) every-line)
    (when-let [diff (run ctx (git-argv "diff" "-U0" "--no-renames" base "--" path))]
      (let [added (set (map first (added-lines diff)))]
        (if (empty? added)
          {:content "" :added added}
          (when-let [text (run ctx ["cat" "--" path])]
            {:content text :added added}))))))

(defn- ^{:stratum 1} artifact-version
  "{:content TEXT :added} against PATH's content at BASE, diffed on the
   host in the owner-only TMP; every line when git cannot diff."
  [ctx base path text tmp name]
  (let [shown (when path (run ctx (git-argv "show" (str base ":" path))))
        old   (if shown (doto (io/file tmp (str name "-base")) (spit shown)) (io/file "/dev/null"))
        new   (doto (io/file tmp name) (spit text))
        diff  (run {} (git-argv "diff" "--no-index" "-U0" "--" (str old) (str new)) #{0 1})]
    (if diff
      {:content text :added (set (map first (added-lines diff)))}
      (every-line text))))

(defn- ^{:stratum 1} history-findings
  "Findings in each commit between BASE and HEAD, each diffed against its
   first parent (so a merge's own edits count), or nil when any part of
   the history cannot be read."
  [ctx base]
  (when-let [revs (run ctx (git-argv "rev-list" "--reverse" (str base "..HEAD")))]
    (reduce (fn [acc sha]
              (let [parent (str sha "^1")
                    files  (run ctx (git-argv "diff" "-z" "--name-only" "--no-renames" parent sha "--"))
                    found  (when files
                             (reduce (fn [found path]
                                       (let [diff  (run ctx (git-argv "diff" "-U0" "--no-renames"
                                                                      parent sha "--" path))
                                             added (set (map first (added-lines diff)))
                                             text  (when (and diff (seq added))
                                                     (run ctx (git-argv "show" (str sha ":" path))))]
                                         (cond
                                           (nil? diff)                 (reduced nil)
                                           (empty? added)              found
                                           (nil? text)                 (reduced nil)
                                           :else (into found (findings path {:content text :added added} sha)))))
                                     [] (nul-split files)))]
                (if found (into acc found) (reduced nil))))
            []
            (remove str/blank? (str/split-lines revs)))))

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
          (vec (distinct (for [[path texts] versions, text texts, f (findings path (every-line text))] f)))

          (nil? history) nil

          :else
          (let [tmp (scratch-dir)]
            (try
              (let [disk (concat (for [p (:tracked listed)] [p (disk-version ctx base p false)])
                                 (for [p (:untracked listed)] [p (disk-version ctx base p true)]))
                    arts (for [[i [path texts]] (map-indexed vector versions)
                               [j text] (map-indexed vector texts)]
                           [path (artifact-version ctx base path text tmp (str i "-" j))])]
                ;; A changed file that could not be read leaves the change
                ;; unchecked: that is a failure, not a pass.
                (when (every? (comp some? second) disk)
                  (vec (distinct (concat (mapcat (fn [[p v]] (findings p v)) (concat disk arts))
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
