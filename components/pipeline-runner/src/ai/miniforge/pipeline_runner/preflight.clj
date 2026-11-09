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
(ns ai.miniforge.pipeline-runner.preflight
  "Preflight: every behavior a pipeline names must resolve before any
   connector opens or any stage is scheduled.

   Required processing by stage family:
     :ingest            a bound connector that implements SourceConnector
     :publish           a bound connector that implements SinkConnector
     :validate          a :stage/quality-pack with at least one rule, every
                        rule carrying a :rule/check-fn
     every other family a :stage/transform whose :transform/type names a
                        function in context :transforms

   A non-connector stage that intentionally skips processing says so with
   `:stage/pass-through? true` in its config. Declaring pass-through and
   processing on the same stage is a conflict. A :stage/spawn-fn on any stage
   must name a function in context :spawn-fns.

   Each problem names the stage id, the problem kind, and the missing ref."
  (:require [ai.miniforge.pipeline-runner.messages :as msg]
            [ai.miniforge.pipeline-runner.preflight.stage-checks :as checks]
            [ai.miniforge.schema.interface :as schema]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Resolution by family.
(defn- ^{:stratum 0} resolution-problems
  "Declared processing must resolve to an implementation."
  [stage context]
  (cond
    (checks/connector-family? stage) []
    (checks/validate-family? stage)  (checks/pack-problems stage)
    :else                            (checks/transform-problems stage context)))

;------------------------------------------------------------------------------ Layer 1

;; One stage.
(defn- ^{:stratum 1} stage-problems
  "Every preflight problem for one stage."
  [stage connectors context]
  (into []
        cat
        [(checks/connector-problems stage connectors)
         (checks/declaration-problems stage)
         (resolution-problems stage context)
         (checks/spawn-problems stage context)]))

;------------------------------------------------------------------------------ Layer 2

;; Whole pipeline.
(defn ^{:stratum 2} check
  "Preflight `pipeline-def` against its bound `connectors` and the execution
   `context` (:transforms, :spawn-fns). Opens nothing and calls no connector.

   Returns (schema/success :preflight {:preflight/problems []}) when every
   named behavior resolves, otherwise a schema/failure whose
   :preflight/problems vector names each stage, problem kind and missing ref."
  [pipeline-def connectors context]
  (let [problems (into []
                       (mapcat #(stage-problems % connectors context))
                       (:pipeline/stages pipeline-def))
        summary  (str/join "; " (map :preflight/message problems))]
    (if (empty? problems)
      (schema/success :preflight {:preflight/problems []})
      (schema/failure :preflight
                      (msg/t :run/preflight-failed {:problems summary})
                      {:preflight/problems problems}))))

(comment
  (check {:pipeline/stages [{:stage/id (random-uuid)
                             :stage/name "Normalize"
                             :stage/family :normalize}]}
         {}
         {}))
