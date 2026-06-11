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

(ns ai.miniforge.workspace.core
  "The single blessed execution-workdir resolver. Fable review §1.3 /
   N11 'no silent downgrade': agent and phase execution code resolved the
   working directory as `(or <ctx-worktree-path> (System/getProperty
   \"user.dir\"))`, silently falling back to the process CWD when the real
   worktree was absent — the wrong-tree source of greenfield/empty work that
   passed gates against the wrong files. This is the intended single home for
   that read; a lint forbidding `(System/getProperty \"user.dir\")` elsewhere
   in execution namespaces is a planned follow-up (it does not exist yet).")

(def ^:private worktree-path-keys
  "Context keys, in priority order, that carry the execution worktree path.
   Union of the keys the migrated call sites previously checked inline."
  [[:execution/worktree-path]
   [:worktree-path]
   [:execution/opts :worktree-path]])

(defn- ctx-worktree-path
  "First non-blank worktree path found on ctx, or nil."
  [ctx]
  (some (fn [ks]
          (let [v (get-in ctx ks)]
            (when (and (string? v) (not (.isBlank ^String v))) v)))
        worktree-path-keys))

(defn- governed?
  "True when ctx indicates a governed (capsule) execution. Governed runs must
   never silently fall back to the host CWD."
  [ctx]
  (= :governed (or (:execution/mode ctx)
                   (get-in ctx [:execution/opts :execution-mode])
                   :local)))

(defn resolve-execution-workdir
  "Resolve the execution worktree path from `ctx`.

   - Worktree path present on ctx → return it.
   - Absent on a GOVERNED run → throw a typed `:anomalies/workdir-unresolved`
     ex-info (fail closed). Silently using the host CWD inside a capsule run
     is how wrong-tree/greenfield work slipped past gates; refuse it.
   - Absent on a local (ungoverned) run → fall back to the process CWD, the
     dev-convenience default. The single intended `user.dir` read.

   `label` is a short site identifier carried into the anomaly / for callers
   that want to log which resolver site degraded."
  ([ctx] (resolve-execution-workdir ctx nil))
  ([ctx label]
   (if-let [wt (ctx-worktree-path ctx)]
     wt
     (if (governed? ctx)
       (throw (ex-info (str "Execution worktree-path unresolved in a governed run"
                            (when label (str " (" label ")"))
                            " — refusing to fall back to the process CWD (no silent downgrade).")
                       {:anomaly/category :anomalies/workdir-unresolved
                        :workspace/site label
                        :execution/mode (or (:execution/mode ctx)
                                            (get-in ctx [:execution/opts :execution-mode])
                                            :local)
                        :workspace/tried worktree-path-keys}))
       (System/getProperty "user.dir")))))
