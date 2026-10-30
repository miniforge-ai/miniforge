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
(ns ai.miniforge.agent.tool-policy
  "The tool policy as data: reading config/agent/tool-policy.edn and
   checking it against its schema, and the paths a tool call names.
   `tool-supervisor` evaluates tool calls against the result."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ToolPolicy
  "Schema for config/agent/tool-policy.edn. Closed, so a misspelt key
   fails the check instead of silently dropping a rule."
  [:map {:closed true}
   [:common [:set :string]]
   [:roles [:map-of :keyword [:set :string]]]
   [:tool/path-keys [:map-of :string [:vector :keyword]]]
   [:tool/glob-keys [:map-of :string [:vector :keyword]]]
   [:tool/protected-paths [:vector :string]]
   [:bash/max-command-length [:int {:min 1}]]
   [:bash/deny-patterns [:vector :string]]])

(defn ^{:stratum 0} worktree-relative
  "`path` relative to the worktree `root`, or nil when it cannot be inside
   it: a path starting with ~, or one whose canonical form (`..` and
   symlinks resolved, as far as the path exists) is not under root's. A
   relative path is taken as relative to root, and root itself is \".\".
   Comparing canonical forms keeps /var and /private/var (macOS) the same
   place and sends a symlink out of the worktree where it points. Git
   metadata is for the caller's `path-ok?` check. The caller tries the path
   both as given and trimmed (`path-candidates`)."
  [root path]
  (let [p (str path)]
    (when-not (str/starts-with? p "~")
      (let [root-c (.getCanonicalFile (io/file (str root)))
            file   (io/file p)
            path-c (.getCanonicalFile (if (.isAbsolute file) file (io/file root-c p)))
            rel    (when (.startsWith (.toPath path-c) (.toPath root-c))
                     (str (.relativize (.toPath root-c) (.toPath path-c))))]
        (if (= "" rel) "." rel)))))

(defn ^{:stratum 0} lexical-relative
  "`path` relative to the worktree `root` as written: `.` and `..`
   resolved, symlinks not, against root as given or its canonical form,
   whichever prefixes the path. nil when neither does, or the path starts
   with ~. The caller checks this form too, so a symlink on the path (a
   linked `.claude/`) cannot hide a protected file or reach `path-ok?`
   already resolved."
  [root path]
  (let [p (str path)]
    (when-not (str/starts-with? p "~")
      (let [file   (io/file p)
            given  (.normalize (.toPath (io/file (str root))))
            canon  (.toPath (.getCanonicalFile (io/file (str root))))
            path-p (.normalize (.toPath (if (.isAbsolute file) file (io/file (str root) p))))
            base   (some #(when (.startsWith path-p ^java.nio.file.Path %) %) [canon given])
            rel    (some-> base (.relativize path-p) str)]
        (if (= "" rel) "." rel)))))

(defn ^{:stratum 0} path-candidates
  "The forms of path input `v` to check: as given, and trimmed. Some
   handlers trim (consider_situation's codex_path), others open the path
   as given (context_write), so both must pass."
  [v]
  (distinct [(str v) (str/trim (str v))]))

(defn ^{:stratum 0} climbs?
  "True when path `p` has a `..` component as written. Refused before any
   normalization: through a symlink, `link/../config` reaches a different
   place than its lexical form `config`."
  [p]
  (boolean (some #{".."} (str/split (str p) #"[/\\\\]"))))

(defn ^{:stratum 0} unix-separators
  "`rel` with Windows separators turned into `/`, so the protected-path
   patterns match a path java.nio printed with backslashes."
  [rel]
  (some-> rel (str/replace "\\" "/")))

(defn ^{:stratum 0} escaping-glob?
  "True when glob `pattern` would search outside the directory it runs in:
   absolute, starting with ~, or with a .. segment."
  [pattern]
  (let [p (str pattern)]
    (boolean (or (str/starts-with? p "/")
                 (str/starts-with? p "~")
                 (some #{".."} (str/split p #"[/\\\\]"))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-tool-policy
  "Parse tool-policy.edn `text`, check it against `ToolPolicy`, and
   compile its :bash/deny-patterns to regexes. Throws an
   :anomalies/incorrect anomaly when the text does not match; the hook
   then refuses the tool call."
  [text]
  (let [policy (edn/read-string text)]
    (when-not (m/validate ToolPolicy policy)
      (response/throw-anomaly! :anomalies/incorrect
                               "config/agent/tool-policy.edn does not match its schema"
                               {:errors (me/humanize (m/explain ToolPolicy policy))}))
    (-> policy
        (update :tool/protected-paths #(mapv re-pattern %))
        (update :bash/deny-patterns #(mapv re-pattern %)))))

(defn ^{:stratum 1} path-refusal
  "Why calling `tool-name` with `tool-input` reaches outside the worktree,
   or nil when it does not. `policy`'s :tool/path-keys and :tool/glob-keys
   say which inputs name a path or a glob. `session` carries :root, the
   worktree, and :path-ok?, (fn [root relative-path]) true when the path
   stays inside root and out of its git metadata. A tool that names a path
   is refused when either is missing.

   Each `path-candidates` form of a path input must have no `..`
   component, resolve inside root (`worktree-relative`), and pass
   `path-ok?` both resolved and as written (`lexical-relative`), so the
   guard's own symlink and metadata checks see both; it is protected when
   either form matches :tool/protected-paths, with `/` separators."
  [policy {:keys [root path-ok?]} tool-name tool-input]
  (let [path-keys (get-in policy [:tool/path-keys tool-name])
        glob-keys (get-in policy [:tool/glob-keys tool-name])
        named     (fn [ks] (keep #(when-some [v (get tool-input %)] [% v]) ks))
        protected (fn [rel] (some #(re-find % (unix-separators rel)) (:tool/protected-paths policy)))]
    (if (and (or (seq path-keys) (seq glob-keys)) (not (and root path-ok?)))
      (str tool-name " names a path, and the hook has no worktree root to check it against")
      (or (some (fn [[k v]]
                  (some (fn [p]
                          (let [rel (worktree-relative root p)
                                lex (lexical-relative root p)]
                            (cond
                              (or (climbs? p)
                                  (not (and rel (path-ok? root rel)))
                                  (and lex (not (path-ok? root lex))))
                              (str (name k) " " (pr-str v) " is outside the worktree or in its git metadata")

                              (or (protected rel) (and lex (protected lex)))
                              (str (name k) " " (pr-str v) " is agent or session config miniforge manages"))))
                        (path-candidates v)))
                (named path-keys))
          (some (fn [[k v]]
                  (when (escaping-glob? v)
                    (str (name k) " " (pr-str v) " searches outside the worktree")))
                (named glob-keys))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (read-tool-policy (slurp (clojure.java.io/resource "config/agent/tool-policy.edn")))
  ;; => the shipped policy, patterns compiled

  (worktree-relative "/work/repo" "/work/repo/src/a.clj") ; => "src/a.clj"
  (worktree-relative "/work/repo" "/etc/passwd")          ; => nil

  :end)
