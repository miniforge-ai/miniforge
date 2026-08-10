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
(ns ai.miniforge.cli.workflow-runner.display
  "Terminal output formatting for workflow execution.

   The vocabularies live in sibling namespaces; this one is the single
   entry point the rest of the workflow runner calls. Each name below is
   its own var, rooted in the implementation fn — not the owning
   namespace's var. So `with-redefs` here intercepts every caller that
   resolves through this namespace, while `with-redefs` on the owning
   namespace's var does not reach those callers (and vice versa).
   Docstrings and arglists live with the implementations."
  (:require
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-demo-line :as demo-line]
   [ai.miniforge.cli.workflow-runner.display-error-help :as error-help]
   [ai.miniforge.cli.workflow-runner.display-event-line :as event-line]
   [ai.miniforge.cli.workflow-runner.display-format :as fmt]
   [ai.miniforge.cli.workflow-runner.display-print :as printing]
   [ai.miniforge.cli.workflow-runner.display-progress :as progress]
   [ai.miniforge.cli.workflow-runner.display-result-facts :as facts]
   [ai.miniforge.cli.workflow-runner.display-summary :as summary]))

;------------------------------------------------------------------------------ Layer 0

;; ANSI styling
(def ^{:stratum 0} ansi-codes ansi/ansi-codes)

(def ^{:stratum 0} colorize ansi/colorize)

;; Scalar formatting
(def ^{:stratum 0} format-duration fmt/format-duration)

;; Lifecycle event lines
(def ^{:stratum 0} format-event-line event-line/format-event-line)

(def ^{:stratum 0} format-demo-line demo-line/format-demo-line)

(def ^{:stratum 0} start-progress! progress/start-progress!)

;; Workflow result readers
(def ^{:stratum 0} extract-failed-tasks facts/extract-failed-tasks)

(def ^{:stratum 0} extract-pr-urls facts/extract-pr-urls)

(def ^{:stratum 0} extract-phase-summaries facts/extract-phase-summaries)

;; Compact summary
(def ^{:stratum 0} format-compact-summary summary/format-compact-summary)

;; Printing
(def ^{:stratum 0} print-workflow-header printing/print-workflow-header)

(def ^{:stratum 0} print-workflow-summary printing/print-workflow-summary)

(def ^{:stratum 0} print-pretty-result printing/print-pretty-result)

(def ^{:stratum 0} print-result printing/print-result)

;; Error help
(def ^{:stratum 0} print-error-header error-help/print-error-header)

(def ^{:stratum 0} print-namespace-resolution-help error-help/print-namespace-resolution-help)

(def ^{:stratum 0} print-babashka-fallback-help error-help/print-babashka-fallback-help)

(def ^{:stratum 0} print-general-debugging-help error-help/print-general-debugging-help)
