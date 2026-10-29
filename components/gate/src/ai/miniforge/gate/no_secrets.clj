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

   The gate scans git objects only, never worktree files, so content and
   line numbers always come from the same bytes:

   1. Snapshot: with a temporary index seeded from HEAD, `git add -A`
      (the staging persistence uses) and `git write-tree` record what a
      commit would publish, clean filters applied.
   2. Each change between two trees -- task base to snapshot, and each
      commit since the base to its first parent, so a merge's own edits
      count -- is listed with `git diff-tree -r -z --no-renames`. Gitlinks
      (mode 160000) have no content here and are skipped. The new blob is
      read with `git cat-file blob`, and its added line numbers come from
      `git diff -U0 --inter-hunk-context=0` between the old and new blob.
   3. The shared secret patterns run over each new blob's whole content
      (a PEM block or a folded header spans lines); a match is reported
      when it touches an added line and exempt only when it lies wholly
      in unchanged lines. Artifact versions not yet written are diffed
      against the base blob of their path the same way.

   Every git call is built by `git-argv` (--literal-pathspecs, unquoted
   paths, and for diff --no-textconv --no-ext-diff --text --no-color
   --inter-hunk-context=0) and run by `run`, where a nonzero exit or an
   executor error reads as nil. Findings beat unreadability: any secret
   found anywhere fails the gate with every finding; only when none is
   found and something could not be read is the result
   :worktree-uninspectable.

   The base is the first resolvable of the artifact's :code/base-refs,
   the context's :execution/task-base-refs (both the implement phase's
   resume-aware candidates), the environment's base SHA, then HEAD. Git
   runs where the checkout is: through :execution/execute-fn in
   :execution/capsule-workdir for governed runs, else on the host in
   :execution/worktree-path. With neither, only the artifact is scanned.

   Detection is redaction/pattern-secret-spans: the shared patterns,
   without the payment-card checksum that ordinary numbers in code pass."
  (:require [ai.miniforge.gate.messages :as msg]
            [ai.miniforge.redaction.interface :as redaction]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run
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

(defn- ^{:stratum 0} git-argv
  "The argv for `git SUB ARGS` with the safe defaults every call gets."
  [sub & args]
  (concat ["git" "--literal-pathspecs" "-c" "core.quotePath=false" sub]
          (when (= "diff" sub)
            ["--no-textconv" "--no-ext-diff" "--text" "--no-color" "--inter-hunk-context=0"])
          args))

(defn- ^{:stratum 0} nul-split
  "NUL-terminated git output as fields, untrimmed: a name may start or end
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
      ;; A context line still occupies a line of the new version.
      (and n (str/starts-with? line " ")) (recur more (inc n) acc)
      :else (recur more n acc))))

(defn ^{:stratum 0} raw-changes
  "Entries of `git diff-tree -r -z` raw output:
   {:path :old-mode :new-mode :old :new}. Public for tests."
  [out]
  (for [[meta path] (partition 2 (remove empty? (str/split (str out) #"\u0000")))
        :let [[old-mode new-mode old new] (str/split (subs meta 1) #" ")]]
    {:path path :old-mode old-mode :new-mode new-mode :old old :new new}))

(defn ^{:stratum 0} scratch-dir
  "A fresh temp directory only its owner can enter, created with that
   mode in one step: the base and artifact copies written into it can
   hold the very credentials being looked for. Public for tests."
  []
  (fs/file (fs/create-temp-dir {:prefix "mf-no-secrets-"
                                :posix-file-permissions "rwx------"})))

(defn- ^{:stratum 0} every-line
  "The set of every line number of TEXT: a version that is new in full."
  [text]
  (set (range 1 (inc (count (str/split-lines (str text)))))))

(defn- ^{:stratum 0} findings
  "{:file :line} (and :commit, when given) for each secret-shaped match
   in CONTENT that touches an ADDED line number, reported at the first
   added line it covers."
  [path content added commit]
  (let [starts  (into [0] (keep-indexed (fn [i c] (when (= c \newline) (inc i)))) content)
        line-of (fn [offset] (count (take-while #(<= % offset) starts)))]
    (for [[start end] (redaction/pattern-secret-spans content)
          :let [hit (first (filter added (range (line-of start)
                                                (inc (line-of (max start (dec end)))))))]
          :when hit]
      (cond-> {:file path :line hit} commit (assoc :commit commit)))))

(defn- ^{:stratum 0} base-candidates
  "Refs to try, in order, for the commit the change is measured from."
  [artifact ctx]
  (concat (:code/base-refs artifact)
          (:execution/task-base-refs ctx)
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

(defn- ^{:stratum 1} snapshot-tree
  "The tree a commit of the worktree would record: `git add -A` into a
   temporary index seeded from HEAD, then `git write-tree`. Nil when
   git cannot build it. The temporary index lives in the git dir and is
   removed."
  [ctx]
  (when-let [git-dir (some-> (run ctx (git-argv "rev-parse" "--absolute-git-dir")) str/trim not-empty)]
    (let [index (str git-dir "/mf-no-secrets-" (random-uuid) ".index")
          with-index #(concat ["env" (str "GIT_INDEX_FILE=" index)] (apply git-argv %&))]
      (try
        (when (and (run ctx (with-index "read-tree" "HEAD"))
                   (run ctx (with-index "add" "-A")))
          (some-> (run ctx (with-index "write-tree")) str/trim not-empty))
        (finally
          (run ctx ["rm" "-f" "--" index]))))))

(defn- ^{:stratum 1} artifact-version-findings
  "Findings in TEXT, the artifact's version of PATH, against PATH's blob
   at BASE (every line when there is no base blob), diffed on the host in
   the owner-only TMP."
  [ctx base path text tmp name]
  (let [shown (when (and base path) (run ctx (git-argv "cat-file" "blob" (str base ":" path))))
        old   (if shown (doto (io/file tmp (str name "-base")) (spit shown)) (io/file "/dev/null"))
        new   (doto (io/file tmp name) (spit text))
        diff  (run {} (git-argv "diff" "--no-index" "-U0" "--" (str old) (str new)) #{0 1})]
    (findings path text (if diff (set (map first (added-lines diff))) (every-line text)) nil)))

(defn- ^{:stratum 1} outgoing-commits
  "The commits between BASE and HEAD, oldest first, or nil when they
   cannot be listed."
  [ctx base]
  (some->> (run ctx (git-argv "rev-list" "--reverse" (str base "..HEAD")))
           str/split-lines
           (remove str/blank?)))

(defn- ^{:stratum 1} changes-findings
  "{:findings [...] :unreadable? bool} for the changes between each
   [old-tree new-tree commit] in PAIRS, tagged with the commit when given."
  [ctx pairs]
  (reduce
   (fn [acc [old-tree new-tree commit]]
     (if-let [out (run ctx (git-argv "diff-tree" "-r" "-z" "--no-renames" old-tree new-tree))]
       (reduce (fn [acc {:keys [path old-mode new-mode old new]}]
                 (let [absent? #(re-matches #"0+" %)
                       skip?   (or (= "160000" new-mode) (absent? new))
                       content (when-not skip? (run ctx (git-argv "cat-file" "blob" new)))
                       added   (cond
                                 (nil? content) nil
                                 (or (absent? old) (= "160000" old-mode)) (every-line content)
                                 :else (some->> (run ctx (git-argv "diff" "-U0" old new))
                                                added-lines (map first) set))]
                   (cond
                     skip? acc
                     (nil? added) (assoc acc :unreadable? true)
                     :else (update acc :findings into (findings path content added commit)))))
               acc
               (raw-changes out))
       (assoc acc :unreadable? true)))
   {:findings [] :unreadable? false}
   pairs))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-no-secrets
  "Gate check (see ns docstring). Findings carry file and line (and the
   commit, for history), never the matched value: the result is logged
   and shown to the agent, so quoting the secret would copy it into both."
  [artifact ctx]
  (let [inspect?  (or (get ctx :execution/execute-fn) (get ctx :execution/worktree-path))
        base      (when inspect? (resolve-base ctx (base-candidates artifact ctx)))
        snapshot  (when base (snapshot-tree ctx))
        tmp       (scratch-dir)
        parts     (try
                    (cond-> [{:findings (vec (for [[i [path texts]] (map-indexed vector (artifact-versions artifact))
                                                   [j text] (map-indexed vector texts)
                                                   f (artifact-version-findings ctx base path text tmp (str i "-" j))]
                                               f))}]
                      inspect? (conj (let [commits (when base (outgoing-commits ctx base))]
                                       (cond-> (changes-findings
                                                ctx (concat (when snapshot [[base snapshot nil]])
                                                            (for [sha commits] [(str sha "^1") sha sha])))
                                         (or (nil? snapshot) (nil? commits)) (assoc :unreadable? true)))))
                    (finally (fs/delete-tree tmp)))
        found     (vec (distinct (mapcat :findings parts)))]
    (cond
      (seq found)
      {:passed? false
       :errors [{:type      :secrets-detected
                 :message   (str (msg/t :no-secrets/detected
                                        {:locations (str/join ", " (map location-text found))})
                                 (when (some :commit found)
                                   (str " " (msg/t :no-secrets/in-history))))
                 :locations found}]}

      (some :unreadable? parts)
      {:passed? false
       :errors [{:type    :worktree-uninspectable
                 :message (msg/t :no-secrets/uninspectable
                                 {:path (str (or (get ctx :execution/capsule-workdir)
                                                 (get ctx :execution/worktree-path)))})}]}

      :else {:passed? true})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (added-lines "@@ -0,0 +1,2 @@\n+a\n+b\n")
  (check-no-secrets {:code/files [{:path "src/a.py"
                                   :content (str "api_key = '" "abcdefgh12" "'")}]} {})
  :leave-this-here)
