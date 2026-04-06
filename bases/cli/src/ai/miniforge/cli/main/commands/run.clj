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

(ns ai.miniforge.cli.main.commands.run
  "Run command — execute workflows from spec files, plan files, or DAG files."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.commands.resume :as resume]))

;------------------------------------------------------------------------------ Layer 0
;; Input type detection

(defn detect-input-type
  "Detect the type of a parsed input file.
   Returns :spec, :dag, :plan, or nil."
  [parsed]
  (cond
    (:spec/title parsed) :spec
    (:dag-id parsed)     :dag
    (:plan/id parsed)    :plan
    :else                nil))

(defn read-edn-file
  "Read an EDN file, returning the parsed data."
  [path]
  (edn/read-string (slurp (str path))))

(defn markdown-spec?
  "True if path has a markdown extension."
  [path]
  (contains? #{"md" "markdown"} (fs/extension (str path))))

;------------------------------------------------------------------------------ Layer 1
;; Shared workflow execution path

(defn- run-spec-workflow
  "Parse, validate, and execute a workflow spec from path.
   Used by both the markdown and EDN code paths."
  [spec-path opts]
  (display/print-info (messages/t :run/parsing-spec {:path spec-path}))
  (let [parsed-spec (spec-parser/parse-spec-file spec-path)
        validation  (spec-parser/validate-spec parsed-spec)]
    (if-not (:valid? validation)
      (do
        (display/print-error (messages/t :run/invalid-spec))
        (doseq [error (:errors validation)]
          (println (messages/t :run/validation-error {:error error}))))
      (do
        (display/print-info (messages/t :run/running-workflow {:title (:spec/title parsed-spec)}))
        (workflow-runner/run-workflow-from-spec!
         parsed-spec
         (cond-> {:output :pretty :quiet false}
           (:backend opts) (assoc :backend (:backend opts))))))))

;------------------------------------------------------------------------------ Layer 2
;; Run command

(defn run-cmd
  "Execute a workflow from a spec, plan, or DAG file.

   Dispatch order:
   1. Markdown files (.md/.markdown) → spec-parser directly (no EDN pre-read)
   2. EDN/JSON files → read and detect type (:spec, :dag, :plan)"
  [opts]
  (let [{:keys [spec interactive]} opts
        resume-id (:resume opts)]
    (cond
      ;; Resume mode
      resume-id
      (resume/resume-workflow resume-id opts)

      ;; Interactive mode
      interactive
      (do
        (display/print-info (messages/t :run/interactive-not-implemented))
        (println (messages/t :run/interactive-coming-soon)))

      ;; No file specified
      (not spec)
      (display/print-error
       (messages/t :run/usage
                   {:command (app-config/command-string "run <spec-file> [--interactive] [--resume <workflow-id>]")}))

      ;; File not found
      (not (fs/exists? spec))
      (display/print-error (messages/t :run/file-not-found {:path spec}))

      ;; Markdown spec — route directly through spec-parser (no EDN pre-read)
      (markdown-spec? spec)
      (try
        (run-spec-workflow spec opts)
        (catch Exception e
          (display/print-error (messages/t :run/failed {:error (ex-message e)}))
          (when-let [data (ex-data e)]
            (println (messages/t :run/error-details {:details (pr-str data)})))))

      ;; EDN/JSON — dispatch based on file content
      :else
      (try
        (let [parsed     (read-edn-file spec)
              input-type (detect-input-type parsed)]
          (case input-type
            ;; Spec file
            :spec
            (run-spec-workflow spec opts)

            ;; DAG or Plan file — execute directly
            (:dag :plan)
            (do
              (display/print-info (messages/t :run/detected-format {:format (name input-type) :path spec}))
              (plan-executor/execute-plan parsed opts))

            ;; Unrecognized format
            (display/print-error
             (messages/t :run/unrecognized-format {:path spec}))))
        (catch Exception e
          (let [error-classification (try
                                       (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                         (when classifier
                                           (classifier e (ex-data e))))
                                       (catch Exception _ nil))]
            (if error-classification
              (display/print-classified-error error-classification)
              (do
                (display/print-error (messages/t :run/failed {:error (ex-message e)}))
                (when-let [data (ex-data e)]
                  (println (messages/t :run/error-details {:details (pr-str data)})))))))))))
