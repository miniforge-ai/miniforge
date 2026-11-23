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
(ns ai.miniforge.pr-lifecycle.policy-eval.fs
  "N13 §2.5 Comment Response Agent — Layer 2: File I/O.

   Apply a single planned fix to the worktree on disk. Each step
   is its own guard function returning a DAG result; the public
   `apply-single-line-replacement!` chains them with `dag/when-let-ok`
   so the pipeline reads top-to-bottom and short-circuits on the
   first typed failure.

   Depends on Layer 1 indirectly via `materialize-fix!`, which
   consumes a planned `:to-apply` entry (the shape produced by
   `policy-eval.plan/plan-fixes`).

   The path guards (`safe-worktree-path`, `safe-worktree-entry-path`)
   are public: `fix-loop/apply-fix-to-worktree` reuses them."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.policy-eval.git-dir :as git-dir]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; ── path safety ──────────────────────────────────────────────────────
(defn ^{:stratum 0} safe-worktree-path
  "Resolve `relative-path` under `worktree-root`. Returns the
   canonical absolute path string when the target stays inside the
   worktree root, or returns nil when `relative-path` escapes
   (path-traversal guard). Use it for reads and writes; deletes use
   `safe-worktree-entry-path`. With
   `{:allow-root? true}` a path that resolves to the root itself also
   passes, as a delete's parent directory may.

   Five layers of defense:
   1. Syntactic — reject absolute paths (leading `/`) and any
      `..`-bearing segments up front. Java's `File(parent, child)`
      constructor converts absolute children into relative ones,
      so a `getCanonicalPath` check alone wouldn't catch
      `/etc/passwd` (it'd resolve to `<worktree>/etc/passwd`).
   2. Symlinks — refuse a path any existing component of which, the
      last included, is a symbolic link, dangling or not. A fix has no
      need to write through a link, and each link is a way to alias
      the git metadata (`policy-eval.git-dir/symlink-on-path?`).
   3. Canonicalization — resolve `.` segments via `getCanonicalPath`.
   4. Containment — require the canonical target to live under
      the canonical worktree root.
   5. Git metadata — refuse a `.git` segment in the path as given,
      and a path whose target is in the git dir, common dir, or hooks
      dir git reports, or is an untracked file a symlink in that
      metadata points at (see `policy-eval.git-dir`). A written hook or
      config runs on the fix loop's next git command. When git cannot
      report them, every path is refused."
  ([worktree-root relative-path]
   (safe-worktree-path worktree-root relative-path nil))
  ([worktree-root relative-path {:keys [allow-root?]}]
   (try
     (let [path-str (str relative-path)]
       (cond
         (str/blank? path-str)
         nil

         ;; Layer 1: syntactic rejection. Absolute paths or any
         ;; ../ component bail before File-API canonicalization.
         (str/starts-with? path-str "/")
         nil

         (some #{".."} (str/split path-str #"[/\\\\]"))
         nil

         (some git-dir/git-dir-name? (git-dir/segments path-str))
         nil

         (git-dir/symlink-on-path? worktree-root path-str)
         nil

         :else
         (let [root   (.getCanonicalPath (io/file (str worktree-root)))
               target (.getCanonicalPath (io/file (str worktree-root) path-str))]
           (when (and (or (str/starts-with? target (str root java.io.File/separator))
                          (and allow-root? (= target root)))
                      (not (git-dir/reaches-metadata? root path-str)))
             target))))
     (catch java.io.IOException _ nil)
     (catch SecurityException _ nil)
     ;; java.nio rejects names it cannot represent (a NUL byte, say).
     (catch IllegalArgumentException _ nil))))

;; ── content read/write (trailing-newline preserving) ─────────────────
(defn- ^{:stratum 0} read-content
  "Slurp `path` and return `{:lines <vec> :trailing-newline? <bool>}`.
   Preserves trailing-newline info so round-trips don't drop the final `\\n`
   or silently normalize CRLF."
  [path]
  (let [content  (slurp path)
        trailing (str/ends-with? content "\n")
        lines    (vec (str/split-lines content))]
    {:lines lines :trailing-newline? trailing}))

(defn- ^{:stratum 0} write-content!
  "Join `lines` with `\\n`, append trailing `\\n` when `trailing-newline?`
   is true, and write to `path`."
  [path lines trailing-newline?]
  (spit path (cond-> (str/join "\n" lines)
               trailing-newline? (str "\n"))))

(defn- ^{:stratum 0} guard-file-exists
  "Step 2: require the resolved file to actually exist."
  [{:keys [abs relative-path] :as ctx}]
  (if (fs/exists? abs)
    (dag/ok ctx)
    (dag/err :policy-eval/file-not-found
             (str "file " abs " not present in worktree")
             {:path relative-path})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} safe-worktree-entry-path
  "Resolve the directory entry `relative-path` names under
   `worktree-root`, for a delete. Returns its absolute path string, or
   nil when the entry would sit outside the worktree or is refused.

   The parent goes through `safe-worktree-path`, which may resolve to
   the worktree root itself (no parent, or `.`), and the last component
   must be a plain name (not `.` or `..`). A path with a symbolic link
   anywhere, the entry itself included, is refused: the fix loop does
   not delete links or anything reached through one.

   The entry itself must not be git metadata either: deleting a linked
   worktree's `.git` file sends the next git command to whatever
   repository contains the worktree directory."
  [worktree-root relative-path]
  (let [file   (io/file (str relative-path))
        leaf   (.getName file)
        parent (.getParent file)]
    (when-not (or (str/blank? leaf)
                  (contains? #{"." ".."} leaf)
                  (git-dir/git-dir-name? leaf)
                  (git-dir/symlink-on-path? worktree-root relative-path))
      (let [entry (some-> (safe-worktree-path worktree-root (or parent ".") {:allow-root? true})
                          (io/file leaf)
                          str)]
        (when-not (and entry (git-dir/metadata-entry? worktree-root entry))
          entry)))))

;; ── pipeline guards ──────────────────────────────────────────────────
;;
;; Each guard takes a context map (or initial args), and returns either
;; `(dag/ok new-ctx)` enriching the context, or `(dag/err ...)` with the
;; typed failure code for this step. The public entry chains them via
;; `dag/when-let-ok` — see the railway-binding rule (dewey 211).
(defn- ^{:stratum 1} guard-path
  "Step 1: resolve `relative-path` under `worktree-path`. Bails out
   with `:policy-eval/path-traversal` on absolute paths, ..-segments,
   canonicalization escapes, a symlink on the path, or a path that
   reaches git metadata."
  [worktree-path relative-path]
  (if-let [abs (safe-worktree-path worktree-path relative-path)]
    (dag/ok {:abs abs :relative-path relative-path :worktree-path worktree-path})
    (dag/err :policy-eval/path-traversal
             (str "relative-path escapes worktree root, passes through a symlink "
                  "(fixes never need to write through one), or reaches its git dir: "
                  relative-path)
             {:path relative-path :worktree-path (str worktree-path)})))

(defn- ^{:stratum 1} guard-line-in-range
  "Step 3: read the file content, verify `line-number` is within
   bounds, and enrich the ctx with the file's lines + the current
   line content for the writer step."
  [{:keys [abs relative-path] :as ctx} line-number]
  (let [{:keys [lines trailing-newline?]} (read-content abs)
        idx (dec line-number)]
    (if (or (neg? idx) (>= idx (count lines)))
      (dag/err :policy-eval/line-out-of-range
               (str "line " line-number " out of range (file has "
                    (count lines) " lines)")
               {:path relative-path :line line-number :file-lines (count lines)})
      (dag/ok (assoc ctx
                     :lines             lines
                     :trailing-newline? trailing-newline?
                     :idx               idx
                     :line              line-number
                     :before            (nth lines idx))))))

(defn- ^{:stratum 1} write-or-skip!
  "Step 4: idempotency guard + write. When `before == replacement`,
   skip the write and return `:already-applied` so the caller can
   exclude the no-op from the commit path. Otherwise rewrite the
   file and report `{:before :after}`."
  [{:keys [abs relative-path lines idx before line trailing-newline?]} replacement]
  (if (= before replacement)
    (dag/ok {:path     relative-path
             :line     line
             :status   :already-applied
             :before   before
             :after    replacement})
    (do
      (write-content! abs (assoc lines idx replacement) trailing-newline?)
      (dag/ok {:path   relative-path
               :line   line
               :before before
               :after  replacement}))))

;------------------------------------------------------------------------------ Layer 2

;; ── public entry ─────────────────────────────────────────────────────
(defn ^{:stratum 2} apply-single-line-replacement!
  "Replace line `line-number` (1-indexed, matching GitHub comment
   line semantics) in `worktree-path/relative-path` with
   `replacement`. Returns DAG result with `{:path :line :before :after}`
   on success, or `{:path :line :status :already-applied}` when
   `before == replacement` (idempotency guard — caller should not
   include this in the commit path).

   Pipeline of typed guards — short-circuits on the first failure:

     guard-path        → :policy-eval/path-traversal
     guard-file-exists → :policy-eval/file-not-found
     guard-line-in-range → :policy-eval/line-out-of-range
     write-or-skip!    → :policy-eval/io-failed (catch-all on throw)"
  [worktree-path relative-path line-number replacement]
  (try
    (dag/when-let-ok
     [path-r  (guard-path worktree-path relative-path)
      exist-r (guard-file-exists (:data path-r))
      range-r (guard-line-in-range (:data exist-r) line-number)]
     (write-or-skip! (:data range-r) replacement))
    (catch Throwable e
      (dag/err :policy-eval/io-failed
               (str "failed to apply fix to " relative-path ": " (.getMessage e))
               {:path relative-path :line line-number}))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} materialize-fix!
  "Apply one planned `:to-apply` entry's suggested-fix to disk.
   Returns the DAG result from `apply-single-line-replacement!`
   decorated with the originating comment-id for traceability."
  [worktree-path entry]
  (let [{:keys [comment payload]} entry
        path (:comment/path comment)
        line (:comment/line comment)
        fix  (:violation/suggested-fix payload)
        r    (apply-single-line-replacement! worktree-path path line fix)]
    (if (dag/ok? r)
      (dag/ok (assoc (:data r) :comment/id (:comment/id comment)))
      (update r :error (fn [err]
                         (-> err
                             (update :data (fnil assoc {})
                                     :comment/id (:comment/id comment))))))))
