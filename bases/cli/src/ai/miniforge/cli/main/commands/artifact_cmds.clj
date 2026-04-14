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
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- artifacts-dir []
  (app-config/artifacts-dir))

(defn- scan-artifact-files []
  (let [dir (io/file (artifacts-dir))]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           (take shared/max-artifacts-display)
           vec)
      [])))

(defn format-file-size
  "Format a byte count into a human-readable size string."
  [bytes]
  (cond
    (< bytes shared/bytes-per-kb) (str bytes "B")
    (< bytes shared/bytes-per-mb) (format "%.1fKB" (/ bytes (double shared/bytes-per-kb)))
    :else                         (format "%.1fMB" (/ bytes (double shared/bytes-per-mb)))))

;------------------------------------------------------------------------------ Layer 1
;; Display helpers

(defn- keyword->str
  "Convert a value to string, rendering keywords as their name."
  [v]
  (if (keyword? v) (name v) (str v)))

(def ^:private provenance-spec
  {:header   :artifact/provenance-header
   :fields   [[:artifact/workflow-id :artifact/provenance-workflow {:transform keyword->str}]
              [:artifact/phase       :artifact/provenance-phase    {:transform keyword->str}]
              [:artifact/agent-id    :artifact/provenance-agent    {:transform keyword->str}]
              [:artifact/git-commit  :artifact/provenance-commit   {:transform keyword->str}]
              [:artifact/created-at  :artifact/provenance-created  {:transform keyword->str}]]
   :sections [{:key :artifact/parent-ids :header :artifact/provenance-parents
               :entry :artifact/provenance-parent-entry :entry-fn (fn [id] {:id id})}
              {:key :artifact/files :header :artifact/provenance-files
               :entry :artifact/provenance-file-entry :entry-fn (fn [p] {:path p})}]})

(defn- display-provenance
  "Render the full provenance block for an artifact."
  [id provenance]
  (display/render-detail (assoc provenance-spec :header-params {:id id}) provenance))

;------------------------------------------------------------------------------ Layer 2
;; Command implementations

(defn artifact-list-cmd
  "List artifacts produced by workflow runs.

   Uses the artifact component's list function if available,
   otherwise scans the configured artifacts directory."
  [_opts]
  (println)
  (println (display/style (messages/t :artifact/header) :foreground :cyan :bold true))
  (println (messages/t :artifact/directory {:dir (artifacts-dir)}))
  (println)
  (let [component-result (shared/try-resolve-fn 'ai.miniforge.artifact.interface/list-artifacts)]
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

(defn artifact-provenance-cmd
  "Show provenance chain for an artifact by ID.

   Provenance includes: workflow run, phase that produced it, agent,
   git commit, and any parent artifacts."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (shared/usage-error! :artifact/provenance-usage "artifact provenance <id>")
      (let [provenance (shared/try-resolve-fn
                        'ai.miniforge.artifact.interface/get-artifact-provenance id)]
        (if provenance
          (display-provenance id provenance)
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
