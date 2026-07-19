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

(ns ai.miniforge.cli.runtime-env
  "MINIFORGE_RUNTIME environment sourcing for container-runtime selection.

   The env var overrides file config per N11-delta §2.1. Lives in its own
   leaf namespace so every CLI path that selects a runtime — the runtime
   doctor commands, the workflow-runner sandbox — reads the same override;
   the dag-executor selector itself stays pure (config in, decision out)."
  (:require
   [clojure.string :as str]))

(def runtime-env-var
  "MINIFORGE_RUNTIME environment variable — overrides file config per
   N11-delta §2.1."
  "MINIFORGE_RUNTIME")

(defn env-runtime-kind
  "Read MINIFORGE_RUNTIME from the environment and coerce to a keyword.
   Returns nil when unset or blank."
  []
  (some-> (System/getenv runtime-env-var) str/trim not-empty keyword))

(defn selection-config
  "Build the config map passed to the runtime selector: `base` (or {} when
   called with no args) with the env override applied on top."
  ([] (selection-config {}))
  ([base]
   (cond-> base
     (env-runtime-kind) (assoc :runtime-kind (env-runtime-kind)))))
