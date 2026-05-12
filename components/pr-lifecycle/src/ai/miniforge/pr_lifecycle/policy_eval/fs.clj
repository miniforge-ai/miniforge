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
   `policy-eval.plan/plan-fixes`)."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ── path safety ──────────────────────────────────────────────────────

(defn- safe-worktree-path
  "Resolve `relative-path` under `worktree-root`. Returns the
   canonical absolute path string when the target stays inside the
   worktree root, or returns nil when `relative-path` escapes
   (path-traversal guard).

   Three layers of defense:
   1. Syntactic — reject absolute paths (leading `/`) and any
      `..`-bearing segments up front. Java's `File(parent, child)`
      constructor converts absolute children into relative ones,
      so a `getCanonicalPath` check alone wouldn't catch
      `/etc/passwd` (it'd resolve to `<worktree>/etc/passwd`).
   2. Canonicalization — resolve symlinks and `.` segments via
      `getCanonicalPath`.
   3. Containment — require the canonical target to live under
      the canonical worktree root."
  [worktree-root relative-path]
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

        :else
        (let [root   (.getCanonicalPath (io/file (str worktree-root)))
              target (.getCanonicalPath (io/file (str worktree-root) path-str))]
          (when (str/starts-with? target (str root java.io.File/separator))
            target))))
    (catch java.io.IOException _ nil)
    (catch SecurityException _ nil)))

;; ── content read/write (trailing-newline preserving) ─────────────────

(defn- read-content
  "Slurp `path` and return `{:lines <vec> :trailing-newline? <bool>}`.
   Preserves trailing-newline info so round-trips don't drop the final `\\n`
   or silently normalize CRLF."
  [path]
  (let [content  (slurp path)
        trailing (str/ends-with? content "\n")
        lines    (vec (str/split-lines content))]
    {:lines lines :trailing-newline? trailing}))

(defn- write-content!
  "Join `lines` with `\\n`, append trailing `\\n` when `trailing-newline?`
   is true, and write to `path`."
  [path lines trailing-newline?]
  (spit path (cond-> (str/join "\n" lines)
               trailing-newline? (str "\n"))))

;; ── pipeline guards ──────────────────────────────────────────────────
;;
;; Each guard takes a context map (or initial args), and returns either
;; `(dag/ok new-ctx)` enriching the context, or `(dag/err ...)` with the
;; typed failure code for this step. The public entry chains them via
;; `dag/when-let-ok` — see the railway-binding rule (dewey 211).

(defn- guard-path
  "Step 1: resolve `relative-path` under `worktree-path`. Bails out
   with `:policy-eval/path-traversal` on absolute paths, ..-segments,
   or canonicalization escapes."
  [worktree-path relative-path]
  (if-let [abs (safe-worktree-path worktree-path relative-path)]
    (dag/ok {:abs abs :relative-path relative-path :worktree-path worktree-path})
    (dag/err :policy-eval/path-traversal
             (str "relative-path escapes worktree root: " relative-path)
             {:path relative-path :worktree-path (str worktree-path)})))

(defn- guard-file-exists
  "Step 2: require the resolved file to actually exist."
  [{:keys [abs relative-path] :as ctx}]
  (if (fs/exists? abs)
    (dag/ok ctx)
    (dag/err :policy-eval/file-not-found
             (str "file " abs " not present in worktree")
             {:path relative-path})))

(defn- guard-line-in-range
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

(defn- write-or-skip!
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

;; ── public entry ─────────────────────────────────────────────────────

(defn apply-single-line-replacement!
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

(defn materialize-fix!
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
