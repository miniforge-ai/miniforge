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

(ns ai.miniforge.cli.main.commands.artifact-cmds
  "Artifact commands: list, provenance.

   Delegates to ai.miniforge.artifact.interface when available.
   Falls back to filesystem scanning of the configured artifacts directory."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- artifacts-dir []
  (app-config/artifacts-dir))

(defn- try-artifact-interface [fn-sym & args]
  (try
    (when-let [f (requiring-resolve fn-sym)]
      (apply f args))
    (catch Exception _ nil)))

(defn- scan-artifact-files []
  (let [dir (io/file (artifacts-dir))]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           (take 50)
           vec)
      [])))

(defn- format-file-size [bytes]
  (cond
    (< bytes 1024)        (str bytes "B")
    (< bytes 1048576)     (format "%.1fKB" (/ bytes 1024.0))
    :else                 (format "%.1fMB" (/ bytes 1048576.0))))

;------------------------------------------------------------------------------ Layer 1
;; Command implementations

(defn artifact-list-cmd
  "List artifacts produced by workflow runs.

   Uses the artifact component's list function if available,
   otherwise scans the configured artifacts directory."
  [_opts]
  (println)
  (println (display/style "Workflow Artifacts" :foreground :cyan :bold true))
  (println (str "  Directory: " (artifacts-dir)))
  (println)
  (let [component-result (try-artifact-interface 'ai.miniforge.artifact.interface/list-artifacts)]
    (cond
      component-result
      (if (seq component-result)
        (doseq [a component-result]
          (println (str "  " (display/style (get a :artifact/id "?") :foreground :bold)
                        "  type:" (get a :artifact/type "unknown")
                        "  wf:"   (get a :artifact/workflow-id "—"))))
        (println "  No artifacts found."))

      :else
      (let [files (scan-artifact-files)]
        (if (seq files)
          (doseq [f files]
            (let [rel-path (str/replace (.getAbsolutePath f)
                                        (str (artifacts-dir) "/") "")]
              (println (str "  " rel-path
                            "  (" (format-file-size (.length f)) ")"))))
          (println "  No artifacts found.")))))
  (println))

(defn artifact-provenance-cmd
  "Show provenance chain for an artifact by ID.

   Provenance includes: workflow run, phase that produced it, agent,
   git commit, and any parent artifacts."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (display/print-error
       (str "Usage: " (app-config/command-string "artifact provenance <id>")))
      (let [provenance (try-artifact-interface
                        'ai.miniforge.artifact.interface/get-artifact-provenance id)]
        (if provenance
          (do
            (println)
            (println (display/style (str "Artifact Provenance: " id) :foreground :cyan :bold true))
            (when-let [wf (:artifact/workflow-id provenance)]
              (println (str "  Workflow:  " wf)))
            (when-let [phase (:artifact/phase provenance)]
              (println (str "  Phase:     " (name phase))))
            (when-let [agent (:artifact/agent-id provenance)]
              (println (str "  Agent:     " agent)))
            (when-let [commit (:artifact/git-commit provenance)]
              (println (str "  Commit:    " commit)))
            (when-let [created (:artifact/created-at provenance)]
              (println (str "  Created:   " created)))
            (when-let [parents (seq (:artifact/parent-ids provenance))]
              (println (str "  Parents:"))
              (doseq [p parents]
                (println (str "    → " p))))
            (when-let [files (seq (:artifact/files provenance))]
              (println (str "  Files:"))
              (doseq [f files]
                (println (str "    " f))))
            (println))
          ;; Fallback: look for artifact file in artifacts dir
          (let [art-file (io/file (str (artifacts-dir) "/" id))]
            (if (.exists art-file)
              (do
                (println)
                (println (display/style (str "Artifact: " id) :foreground :cyan :bold true))
                (println (str "  Path:  " (.getAbsolutePath art-file)))
                (println (str "  Size:  " (format-file-size (.length art-file))))
                (println (str "  Note:  provenance-bundle component not available"))
                (println))
              (display/print-error (str "Artifact not found: " id)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (artifact-list-cmd {})
  (artifact-provenance-cmd {:id "some-artifact-id"})
  :end)
