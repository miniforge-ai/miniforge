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

(ns harvest
  "Pull persisted task bundles back into the host repo as named branches.

   Each `bb dogfood` (or any miniforge run) writes a `.bundle` file per
   task under `~/.miniforge/checkpoints/<workflow-id>/<task-id>.bundle`.
   The bundles preserve the agent's work as a real git commit on the
   task branch, but nothing automatically merges them into the host
   working tree because tasks running off the same base often produce
   conflicting alternative cuts of the same files.

   Harvest is the explicit, user-driven recovery step:
     bb harvest                  # list all checkpoint workflows
     bb harvest <workflow-id>    # fetch all bundles for one workflow
     bb harvest --all            # fetch every bundle on disk

   Each bundle becomes a `harvest/<workflow-id>/<task-id>` ref in the
   host repo. Use `git log` / `git checkout` / `git cherry-pick` to
   inspect or integrate."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

;; ──────────────────────────────────────────────────────────────────────
;; Layer 0 — paths

(def ^:private default-checkpoint-dir
  (str (System/getProperty "user.home") "/.miniforge/checkpoints"))

(defn- checkpoint-dir
  "Resolve checkpoint root: env override → default."
  []
  (or (System/getenv "MINIFORGE_CHECKPOINT_DIR")
      default-checkpoint-dir))

;; ──────────────────────────────────────────────────────────────────────
;; Layer 1 — discovery

(defn- workflow-dirs
  "Return [workflow-id absolute-path] pairs under checkpoint root."
  [root]
  (when (fs/exists? root)
    (->> (fs/list-dir root)
         (filter fs/directory?)
         (mapv (fn [d] [(fs/file-name d) (str d)])))))

(defn- bundles-in
  "Return [task-id absolute-path] pairs for .bundle files in a workflow dir."
  [workflow-path]
  (->> (fs/list-dir workflow-path)
       (filter #(str/ends-with? (fs/file-name %) ".bundle"))
       (mapv (fn [b]
               [(str/replace (fs/file-name b) #"\.bundle$" "")
                (str b)]))
       (sort-by first)))

;; ──────────────────────────────────────────────────────────────────────
;; Layer 2 — git operations

(defn- run-git
  "Run a git command, return {:exit :out :err}."
  [& args]
  (let [{:keys [exit out err]}
        (apply p/sh {:continue true :out :string :err :string} "git" args)]
    {:exit exit :out (or out "") :err (or err "")}))

(defn- in-git-repo? []
  (zero? (:exit (run-git "rev-parse" "--git-dir"))))

(defn- fetch-bundle!
  "git fetch <bundle> HEAD:<ref>. Returns {:ok? :ref :err}."
  [bundle-path ref]
  (let [r (run-git "fetch" bundle-path (str "HEAD:" ref))]
    (if (zero? (:exit r))
      {:ok? true :ref ref}
      {:ok? false :ref ref :err (str/trim (:err r))})))

;; ──────────────────────────────────────────────────────────────────────
;; Layer 3 — commands

(defn- list-checkpoints
  "Print every workflow checkpoint dir, with bundle counts."
  []
  (let [root (checkpoint-dir)
        wfs  (workflow-dirs root)]
    (cond
      (not (fs/exists? root))
      (do (println "No checkpoint root yet:" root)
          (println "Run `bb dogfood` (or any miniforge workflow) to create one."))

      (empty? wfs)
      (println "Checkpoint root is empty:" root)

      :else
      (do
        (println "Checkpoint root:" root)
        (doseq [[wf-id path] wfs
                :let [bundles (bundles-in path)]]
          (println (format "  %s  (%d bundle%s)"
                           wf-id
                           (count bundles)
                           (if (= 1 (count bundles)) "" "s"))))
        (println)
        (println "Pull a workflow's work back into this repo:")
        (println "  bb harvest <workflow-id>")
        (println "Or pull everything:")
        (println "  bb harvest --all")))))

(defn- harvest-workflow!
  "Fetch every bundle for one workflow-id into refs/harvest/<workflow-id>/<task-id>."
  [workflow-id]
  (let [root         (checkpoint-dir)
        wf-path      (str root "/" workflow-id)]
    (cond
      (not (fs/exists? wf-path))
      (do
        (println "No such workflow checkpoint dir:" wf-path)
        (System/exit 1))

      (not (in-git-repo?))
      (do
        (println "Not inside a git repository — harvest needs a host repo to fetch into.")
        (System/exit 1))

      :else
      (let [bundles (bundles-in wf-path)]
        (when (empty? bundles)
          (println "No bundles found in" wf-path)
          (System/exit 1))
        (println (format "Harvesting %d bundle(s) from workflow %s → refs/heads/harvest/%s/<task-id>"
                         (count bundles) workflow-id workflow-id))
        (doseq [[task-id bundle-path] bundles
                :let [ref (str "harvest/" workflow-id "/" task-id)
                      r   (fetch-bundle! bundle-path ref)]]
          (if (:ok? r)
            (println (format "  ✓ %s  ← %s" ref bundle-path))
            (println (format "  ✗ %s  (%s)" ref (:err r)))))
        (println)
        (println "Inspect a task's work:")
        (println (format "  git log harvest/%s/<task-id>" workflow-id))
        (println (format "  git checkout harvest/%s/<task-id>" workflow-id))))))

(defn- harvest-all!
  "Harvest every workflow checkpoint."
  []
  (let [root (checkpoint-dir)
        wfs  (workflow-dirs root)]
    (when (empty? wfs)
      (println "Nothing to harvest — checkpoint root is empty:" root)
      (System/exit 0))
    (doseq [[wf-id _] wfs]
      (println "─── workflow:" wf-id)
      (harvest-workflow! wf-id)
      (println))))

(defn run
  "Entry point. Usage:
     bb harvest                  # list workflows
     bb harvest <workflow-id>    # fetch all bundles for one workflow
     bb harvest --all            # fetch every bundle on disk"
  [& args]
  (let [arg (first args)]
    (cond
      (or (nil? arg) (= arg "--list") (= arg "list"))  (list-checkpoints)
      (= arg "--all")                                  (harvest-all!)
      (str/starts-with? (str arg) "-")
      (do (println "Unknown option:" arg)
          (println "Usage: bb harvest [--all | <workflow-id>]")
          (System/exit 1))
      :else (harvest-workflow! arg))))
