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

(ns ai.miniforge.workflow.runner-defaults
  "Configuration-as-data for the workflow runner.
   All constants live in config/workflow/runner/defaults.edn.

   Malli schema for :runner/defaults (documented in the EDN file header):

     [:map {:closed false}
      [:max-phases                     {:optional true} :int]
      [:max-redirects                  {:optional true} :int]
      [:max-infra-retries              {:optional true} :int]
      [:max-consecutive-phase-retries  {:optional true} :int]
      [:max-backoff-ms                 {:optional true} :int]
      [:backoff-base-ms                {:optional true} :int]
      [:pause-poll-interval-ms         {:optional true} :int]
      [:default-workdir                {:optional true} :string]
      [:task-branch-prefix             {:optional true} :string]
      [:default-docker-image           {:optional true} :string]]"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private defaults
  "Loaded once from classpath resource."
  (delay
    (if-let [r (io/resource "config/workflow/runner/defaults.edn")]
      (:runner/defaults (edn/read-string (slurp r)))
      {})))

(defn max-phases           [] (get @defaults :max-phases 50))
(defn max-redirects        [] (get @defaults :max-redirects 5))
;; Infra-retry budget (Fable §2.4): transient infrastructure verdicts
;; (timeout/rate-limit/backend-died) retry the SAME phase up to this many
;; times — a budget DISTINCT from max-redirects, so a flaky provider can't
;; burn the work/redirect budget. EDN value is 1 (one retry per phase-LLM
;; call). Fallback of 3 is a safety net when the EDN resource is absent.
(defn max-infra-retries    [] (get @defaults :max-infra-retries 3))
(defn max-consecutive-phase-retries
  "Cap on how many times the SAME phase may run in immediately consecutive
   pipeline iterations before the workflow fails loud. Distinct from
   `max-phases` (total steps) and `max-redirects` (phase→phase redirects):
   this catches a single phase retrying itself in place (e.g. implement
   failing + re-entering on every agent stall), which would otherwise burn
   up to `max-phases` iterations — hours of tokens — before stopping."
  [] (get @defaults :max-consecutive-phase-retries 3))
(defn max-backoff-ms       [] (get @defaults :max-backoff-ms 30000))
(defn backoff-base-ms      [] (get @defaults :backoff-base-ms 1000))
(defn pause-poll-interval-ms [] (get @defaults :pause-poll-interval-ms 1000))
(defn default-workdir      [] (get @defaults :default-workdir "/workspace"))
(defn task-branch-prefix   [] (get @defaults :task-branch-prefix "task/"))
(defn default-docker-image [] (get @defaults :default-docker-image "miniforge/task-runner-clojure:latest"))
