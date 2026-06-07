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

(ns ai.miniforge.workflow-financial-etl.interface
  "Public API for ETL product-owned execution helpers."
  (:require
   [ai.miniforge.workflow-financial-etl.core :as core]
   [ai.miniforge.workflow-financial-etl.events :as events]))

(def create-etl-state
  "Build the initial in-memory state map for an ETL workflow run.

   Args: workflow-id (any id value), sources (collection of source descriptors).
   Returns a map with keys :workflow/id, :workflow/type (:etl),
   :workflow/status (:running), :workflow/started-at (java.util.Date),
   :etl/sources (the passed sources), and :etl/stats (a map of zeroed counters
   :packs-generated, :packs-promoted, :high-risk-findings, :sources-processed).
   Pure; does not throw."
  core/create-etl-state)

(def classify-sources
  "Classify ETL sources (first pipeline stage).

   Args: logger, sources (collection). Returns a schema success/failure map.
   On success: {:success? true :classified <sources> ...}. On error: an
   exception-failure map {:success? false :classified nil :error {...}
   :anomaly {...} :stage :classification}. Catches exceptions internally and
   returns a failure map rather than throwing."
  core/classify-sources)

(def scan-sources
  "Scan classified sources for findings (second pipeline stage).

   Args: logger, classified-sources (collection). Returns a schema
   success/failure map. On success: {:success? true :scanned <sources>
   :findings <vector>}. On error: exception-failure map {:success? false
   :scanned nil :error {...} :anomaly {...} :stage :scanning}. Catches
   exceptions internally; does not throw."
  core/scan-sources)

(def extract-knowledge
  "Extract knowledge packs from scanned sources (third pipeline stage).

   Args: logger, scanned-sources (collection). Returns a schema success/failure
   map. On success: {:success? true :packs <vector>}. On error:
   exception-failure map {:success? false :packs nil :error {...} :anomaly {...}
   :stage :extraction}. Catches exceptions internally; does not throw."
  core/extract-knowledge)

(def validate-packs
  "Validate extracted packs (fourth pipeline stage).

   Args: logger, packs (collection). Returns a schema success/failure map.
   On success: {:success? true :validated <packs>}. On error:
   exception-failure map {:success? false :validated nil :error {...}
   :anomaly {...} :stage :validation}. Catches exceptions internally;
   does not throw."
  core/validate-packs)

(def run-etl-workflow
  "Run the full ETL pipeline (classify -> scan -> extract -> validate).

   Args: logger, workflow-id, sources (collection). Returns a schema
   success/failure map. On success: {:success? true :packs <validated-packs>
   :stats <stats-map> :duration-ms <long>} and emits an etl-completed event.
   On the first failing stage: a failure map {:success? false :packs nil
   :error <stage-error> :duration-ms <long> :stats <merged-stats>} and emits an
   etl-failed event. Does not throw (stage failures are returned as maps)."
  core/run-etl-workflow)

(def etl-completed-event
  "Construct an ETL-completed event map (no side effects).

   Args: workflow-id, duration-ms, summary (stats map). Returns a map with the
   common event-base keys (:event/type :etl/completed, :event/id (uuid),
   :event/timestamp (java.util.Date), :event/version, :event/sequence-number,
   :workflow/id) plus :etl/duration-ms, :etl/summary, and a :message string."
  events/etl-completed-event)

(def etl-failed-event
  "Construct an ETL-failed event map (no side effects).

   Args: workflow-id, failure-stage, failure-reason, and optional error-details.
   Returns a map with the common event-base keys plus :etl/failure-stage,
   :etl/failure-reason, and a :message string; when error-details is supplied it
   is attached under :etl/error-details."
  events/etl-failed-event)

(def emit-etl-completed
  "Log an ETL-completed event at info level via the logger.

   Args: logger, workflow-id, duration-ms, summary (stats map). Builds the
   completed-event map and writes it through the logger. Returns the logger
   call's value; called for its logging side effect."
  events/emit-etl-completed)

(def emit-etl-failed
  "Log an ETL-failed event at error level via the logger.

   Args: logger, workflow-id, failure-stage, failure-reason, and optional
   error-details. Builds the failed-event map and writes it through the logger.
   Returns the logger call's value; called for its logging side effect."
  events/emit-etl-failed)
