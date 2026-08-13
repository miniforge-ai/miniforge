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

   Uses ai.miniforge.artifact.interface directly, falling back to filesystem
   scanning of the configured artifacts directory when the store cannot be queried.

   Provenance rendering (the header/fields/sections spec and its renderer)
   lives in the sibling ai.miniforge.cli.main.commands.artifact-cmds.provenance-view
   namespace (rule 210: kept here, it pushed this file to 4 real layers, max 3)."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.artifact-cmds.provenance-view :as provenance-view]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers
(defn- ^{:stratum 0} artifacts-dir []
  (app-config/artifacts-dir))

(defn- ^{:stratum 0} create-artifact-store []
  (artifact/create-transit-store {:dir (app-config/home-dir)}))

(defn ^{:stratum 0} format-file-size
  "Format a byte count into a human-readable size string."
  [bytes]
  (cond
    (< bytes shared/bytes-per-kb) (str bytes "B")
    (< bytes shared/bytes-per-mb) (format "%.1fKB" (/ bytes (double shared/bytes-per-kb)))
    :else                         (format "%.1fMB" (/ bytes (double shared/bytes-per-mb)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} scan-artifact-files []
  (let [dir (io/file (artifacts-dir))]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           (take shared/max-artifacts-display)
           vec)
      [])))

(defn- ^{:stratum 1} list-component-artifacts []
  (try
    (vec (artifact/query (create-artifact-store) {}))
    (catch Exception _ nil)))

(defn- ^{:stratum 1} get-component-provenance [id]
  (try
    (artifact/get-provenance (create-artifact-store) id)
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2

;; Command implementations
(defn ^{:stratum 2} artifact-list-cmd
  "List artifacts produced by workflow runs.

   Uses the artifact component's list function if available,
   otherwise scans the configured artifacts directory."
  [_opts]
  (println)
  (println (display/style (messages/t :artifact/header) :foreground :cyan :bold true))
  (println (messages/t :artifact/directory {:dir (artifacts-dir)}))
  (println)
  (let [component-result (list-component-artifacts)]
    (cond
      component-result
      (if (seq component-result)
        (doseq [a component-result]
          (println (messages/t :artifact/entry
                              {:id          (display/style (get a :artifact/id "?") :foreground :bold)
                               :type        (get a :artifact/type "unknown")
                               :workflow-id (get a :artifact/workflow-id "—")})))
        (println (messages/t :artifact/none)))

      :else
      (let [files (scan-artifact-files)]
        (if (seq files)
          (doseq [f files]
            (let [rel-path (str/replace (.getAbsolutePath f)
                                        (str (artifacts-dir) "/") "")]
              (println (messages/t :artifact/file-entry
                                  {:path rel-path
                                   :size (format-file-size (.length f))}))))
          (println (messages/t :artifact/none))))))
  (println))

(defn ^{:stratum 2} artifact-provenance-cmd
  "Show provenance chain for an artifact by ID.

   Provenance includes: workflow run, phase that produced it, agent,
   git commit, and any parent artifacts."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (shared/usage-error! :artifact/provenance-usage "artifact provenance <id>")
      (let [provenance (get-component-provenance id)]
        (if provenance
          (provenance-view/display-provenance id provenance)
          ;; Fallback: look for artifact file in artifacts dir
          (let [art-file (io/file (str (artifacts-dir) "/" id))]
            (if (.exists art-file)
              (do
                (display/render-detail
                 {:header        :artifact/file-header
                  :header-params {:id id}
                  :fields        [[:file/path :artifact/file-path {:param :path}]
                                  [:file/size :artifact/file-size {:param :size}]]}
                 {:file/path (.getAbsolutePath art-file)
                  :file/size (format-file-size (.length art-file))})
                (println (messages/t :artifact/no-provenance)))
              (do (display/print-error (messages/t :artifact/not-found {:id id}))
                  (shared/exit! 1)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (artifact-list-cmd {})
  (artifact-provenance-cmd {:id "some-artifact-id"})
  :end)
