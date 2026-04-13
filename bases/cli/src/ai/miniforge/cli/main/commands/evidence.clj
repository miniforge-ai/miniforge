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

(ns ai.miniforge.cli.main.commands.evidence
  "Evidence bundle commands: show, export, list.

   Delegates to ai.miniforge.evidence-bundle.interface when available.
   Falls back to filesystem scanning of ~/.miniforge/evidence/."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- evidence-dir []
  (str (app-config/home-dir) "/evidence"))

(defn- try-evidence-interface [fn-sym & args]
  (try
    (when-let [f (requiring-resolve fn-sym)]
      (apply f args))
    (catch Exception _ nil)))

(defn- scan-evidence-dir []
  (let [dir (io/file (evidence-dir))]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           vec))))

(defn- load-bundle-from-file [file]
  (try
    (cond
      (str/ends-with? (.getName file) ".edn")
      (edn/read-string (slurp file))

      :else nil)
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Command implementations

(defn evidence-list-cmd
  "List all available evidence bundles.

   Shows bundles from the evidence-bundle component if available,
   otherwise scans ~/.miniforge/evidence/."
  [_opts]
  (println)
  (println (display/style "Evidence Bundles" :foreground :cyan :bold true))
  (println)
  (let [component-result (try-evidence-interface 'ai.miniforge.evidence-bundle.interface/list-bundles)]
    (cond
      component-result
      (if (seq component-result)
        (doseq [bundle component-result]
          (println (str "  " (display/style (get bundle :bundle/id "unknown") :foreground :bold)
                        "  wf:" (get bundle :bundle/workflow-id "—")
                        "  (" (get bundle :bundle/status "unknown") ")")))
        (println "  No evidence bundles found."))

      :else
      (let [files (scan-evidence-dir)]
        (if (seq files)
          (doseq [f files]
            (let [bundle (load-bundle-from-file f)
                  id     (if bundle
                           (or (some-> (:bundle/id bundle) str) (.getName f))
                           (.getName f))]
              (println (str "  " (display/style id :foreground :bold)
                            (when (and bundle (:bundle/workflow-id bundle))
                              (str "  wf:" (:bundle/workflow-id bundle)))
                            (when (and bundle (:bundle/status bundle))
                              (str "  (" (:bundle/status bundle) ")"))))))
          (do
            (println "  No evidence bundles found.")
            (println (str "  Evidence dir: " (evidence-dir))))))))
  (println))

(defn evidence-show-cmd
  "Show the contents of an evidence bundle by ID."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (do (display/print-error
           (str "Usage: " (app-config/command-string "evidence show <id>")))
          (System/exit 1))
      (let [bundle (or (try-evidence-interface 'ai.miniforge.evidence-bundle.interface/get-bundle id)
                       ;; Filesystem fallback
                       (let [f (io/file (str (evidence-dir) "/" id ".edn"))]
                         (when (.exists f) (load-bundle-from-file f))))]
        (if-not bundle
          (do (display/print-error (str "Evidence bundle not found: " id
                                      "\nRun `" (app-config/command-string "evidence list") "` to see available bundles."))
              (System/exit 1))
          (do
            (println)
            (println (display/style (str "Evidence Bundle: " id) :foreground :cyan :bold true))
            (println (str "  Workflow:  " (get bundle :bundle/workflow-id "—")))
            (println (str "  Status:    " (get bundle :bundle/status "unknown")))
            (println (str "  Created:   " (get bundle :bundle/created-at "—")))
            (when-let [artifacts (:bundle/artifacts bundle)]
              (println (str "  Artifacts: " (count artifacts)))
              (doseq [a (take 10 artifacts)]
                (println (str "    • " (get a :artifact/type "unknown")
                              " — " (get a :artifact/id "")))))
            (when-let [phases (:bundle/phases bundle)]
              (println (str "  Phases:    " (count phases))))
            (println)))))))

(defn evidence-export-cmd
  "Export an evidence bundle to a file in the requested format.

   Supported formats: edn (default), json, html."
  [opts]
  (let [{:keys [id format]} opts
        fmt (or format "edn")]
    (if-not id
      (do (display/print-error
           (str "Usage: " (app-config/command-string "evidence export <id> <format>")))
          (System/exit 1))
      (let [result (try-evidence-interface
                    'ai.miniforge.evidence-bundle.interface/export-bundle id fmt)]
        (if result
          (do
            (display/print-success (str "Exported evidence bundle: " id))
            (println (str "  Format: " fmt))
            (when-let [path (:path result)]
              (println (str "  Path:   " path))))
          ;; Fallback: write raw EDN from filesystem
          (let [src (io/file (str (evidence-dir) "/" id ".edn"))]
            (if (.exists src)
              (let [dest (str (evidence-dir) "/" id "-export." fmt)]
                (fs/copy (str src) dest {:replace-existing true})
                (display/print-success (str "Exported (raw): " dest)))
              (do (display/print-error (str "Evidence bundle not found: " id))
                  (System/exit 1)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (evidence-list-cmd {})
  (evidence-show-cmd {:id "some-bundle-id"})
  (evidence-export-cmd {:id "some-bundle-id" :format "json"})
  :end)
