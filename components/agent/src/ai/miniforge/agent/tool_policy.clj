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
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.nio.file Paths]))

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
   [:bash/deny-patterns [:vector :string]]])

(defn ^{:stratum 0} worktree-relative
  "`path` relative to the worktree `root`, or nil when it cannot be inside
   it: a path starting with ~, an absolute path that is not under root,
   or a relative one that climbs out of it, once `.` and `..` are resolved
   lexically. A relative path is taken as relative to root, and root
   itself is \".\". Symlinks and git metadata are for the caller's
   `path-ok?` check."
  [root path]
  (let [p (str path)]
    (when-not (str/starts-with? p "~")
      (let [root-p (.normalize (Paths/get (str root) (make-array String 0)))
            path-p (.normalize (Paths/get p (make-array String 0)))
            rel    (cond
                     (.isAbsolute path-p)        (when (.startsWith path-p root-p)
                                                   (str (.relativize root-p path-p)))
                     (.startsWith path-p "..")   nil
                     :else                       (str path-p))]
        (if (= "" rel) "." rel)))))

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
   is refused when either is missing."
  [policy {:keys [root path-ok?]} tool-name tool-input]
  (let [path-keys (get-in policy [:tool/path-keys tool-name])
        glob-keys (get-in policy [:tool/glob-keys tool-name])
        named     (fn [ks] (keep #(when-some [v (get tool-input %)] [% v]) ks))
        protected (fn [rel] (some #(re-find % rel) (:tool/protected-paths policy)))]
    (if (and (or (seq path-keys) (seq glob-keys)) (not (and root path-ok?)))
      (str tool-name " names a path, and the hook has no worktree root to check it against")
      (or (some (fn [[k v]]
                  (let [rel (worktree-relative root v)]
                    (cond
                      (not (and rel (path-ok? root rel)))
                      (str (name k) " " (pr-str v) " is outside the worktree or in its git metadata")

                      (protected rel)
                      (str (name k) " " (pr-str v) " is agent or session config miniforge manages"))))
                (named path-keys))
          (some (fn [[k v]]
                  (when (escaping-glob? v)
                    (str (name k) " " (pr-str v) " searches outside the worktree")))
                (named glob-keys))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (read-tool-policy "{:common #{} :roles {:reviewer #{}} :bash/deny-patterns []}")
  ;; => {:common #{} :roles {:reviewer #{}} :bash/deny-patterns []}

  (read-tool-policy "{:common [\"Read\"] :roles {} :bash/deny-patterns []}")
  ;; throws: :common should be a set

  :end)
