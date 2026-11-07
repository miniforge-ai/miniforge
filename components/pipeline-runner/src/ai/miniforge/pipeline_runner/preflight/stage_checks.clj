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
(ns ai.miniforge.pipeline-runner.preflight.stage-checks
  "Per-stage preflight checks. Each `*-problems` fn inspects one concern of
   one stage and returns a vector of problem maps; an empty vector means the
   concern resolves. `ai.miniforge.pipeline-runner.preflight` composes them."
  (:require [ai.miniforge.connector.interface :as conn]
            [ai.miniforge.pipeline-runner.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

;; Stage declarations and resolution predicates.
(def ^{:stratum 0} ^:private connector-families
  "Families that run through a connector, the protocol the bound connector must
   implement, and the role reported when it does not."
  {:ingest  {:protocol conn/SourceConnector :role :connector/source}
   :publish {:protocol conn/SinkConnector   :role :connector/sink}})

(def ^{:stratum 0} ^:private problem-message-keys
  {:connector/missing       :preflight/connector-missing
   :connector/role          :preflight/connector-role
   :transform/unresolved    :preflight/transform-unresolved
   :processing/undeclared   :preflight/processing-undeclared
   :pass-through/conflict   :preflight/pass-through-conflict
   :quality-pack/empty      :preflight/quality-pack-empty
   :quality-pack/malformed  :preflight/quality-pack-malformed
   :quality-rule/unresolved :preflight/quality-rule-unresolved
   :quality-rule/invalid    :preflight/quality-rule-invalid
   :spawn-fn/unresolved     :preflight/spawn-fn-unresolved})

(defn- ^{:stratum 0} pass-through? [stage]
  (true? (get-in stage [:stage/config :stage/pass-through?])))

(defn- ^{:stratum 0} callable?
  "What the executor can call: a fn, a Var holding one, or a multimethod."
  [x]
  (or (fn? x) (var? x) (instance? clojure.lang.MultiFn x)))

(defn- ^{:stratum 0} transform-declaration [stage]
  (get-in stage [:stage/config :stage/transform]))

(defn- ^{:stratum 0} malformed-rules?
  "True when a quality pack's :pack/rules is present but not a list of rules."
  [pack]
  (let [rules (:pack/rules pack)]
    (and (some? rules) (not (sequential? rules)))))

(defn- ^{:stratum 0} transform-type [stage]
  (get-in stage [:stage/config :stage/transform :transform/type]))

(defn- ^{:stratum 0} quality-pack [stage]
  (get-in stage [:stage/config :stage/quality-pack]))

(defn- ^{:stratum 0} spawn-key [stage]
  (get-in stage [:stage/config :stage/spawn-fn]))

(defn ^{:stratum 0} validate-family? [stage]
  (= :validate (:stage/family stage)))

(defn- ^{:stratum 0} rule-ref
  "Name an unresolved rule by its id, or by its type when it has no id."
  [rule]
  (or (:rule/id rule) (:rule/type rule)))

(defn- ^{:stratum 0} implements-role?
  "A connector serves a family only if it implements the base Connector
   protocol (connect/close) as well as the family's protocol."
  [protocol connector]
  (and (satisfies? conn/Connector connector)
       (satisfies? protocol connector)))

(defn- ^{:stratum 0} rule-problem-kind
  "A rule the resolver kept with a :rule/error was configured but could not be
   built; any other rule without a check fn has no implementation."
  [rule]
  (if (:rule/error rule) :quality-rule/invalid :quality-rule/unresolved))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} registered-fn? [context registry-key k]
  (callable? (get-in context [registry-key k])))

(defn- ^{:stratum 1} any-processing?
  "True when the stage declares a quality pack or a transform, whatever its
   family; pass-through beside either is a conflict."
  [stage]
  (or (some? (quality-pack stage)) (some? (transform-declaration stage))))

;; Problem values and derived declarations.
(defn- ^{:stratum 1} problem
  "One preflight problem for `stage`: what kind, and which ref is missing."
  [stage kind ref]
  (let [stage-name (:stage/name stage)
        message    (msg/t (get problem-message-keys kind) {:stage stage-name :ref ref})]
    {:stage/id          (:stage/id stage)
     :stage/name        stage-name
     :preflight/problem kind
     :preflight/ref     ref
     :preflight/message message}))

(defn ^{:stratum 1} connector-family? [stage]
  (contains? connector-families (:stage/family stage)))

(defn- ^{:stratum 1} declared-processing
  "The processing a non-connector stage declares: its quality pack for a
   :validate stage, its transform type otherwise."
  [stage]
  (if (validate-family? stage) (quality-pack stage) (transform-type stage)))

(defn- ^{:stratum 1} unresolved-rules
  "The rules in a quality pack that carry no check fn."
  [pack]
  (remove #(callable? (:rule/check-fn %)) (get pack :pack/rules [])))

;------------------------------------------------------------------------------ Layer 2

;; One check per concern.
(defn ^{:stratum 2} connector-problems
  "A connector-family stage needs a bound connector implementing its role."
  [stage connectors]
  (let [{:keys [protocol role]} (get connector-families (:stage/family stage))
        ref                     (:stage/connector-ref stage)
        connector               (get connectors ref)]
    (cond
      (nil? protocol)                             []
      (nil? connector)                            [(problem stage :connector/missing ref)]
      (not (implements-role? protocol connector)) [(problem stage :connector/role role)]
      :else                                       [])))

(defn ^{:stratum 2} declaration-problems
  "A non-connector stage declares processing or pass-through, not both, not
   neither."
  [stage]
  (let [declared? (some? (declared-processing stage))
        pass?     (pass-through? stage)
        family    (:stage/family stage)]
    (cond
      (connector-family? stage)           []
      (and pass? (any-processing? stage)) [(problem stage :pass-through/conflict family)]
      (or declared? pass?)                []
      :else                               [(problem stage :processing/undeclared family)])))

(defn ^{:stratum 2} transform-problems
  "A declared transform type must name a registered function."
  [stage context]
  (let [ttype (transform-type stage)]
    (if (or (nil? ttype) (registered-fn? context :transforms ttype))
      []
      [(problem stage :transform/unresolved ttype)])))

(defn ^{:stratum 2} spawn-problems
  "A named spawn function must be registered."
  [stage context]
  (let [k (spawn-key stage)]
    (if (or (nil? k) (registered-fn? context :spawn-fns k))
      []
      [(problem stage :spawn-fn/unresolved k)])))

(defn ^{:stratum 2} pack-problems
  "A declared quality pack needs at least one rule, and every rule needs an
   implementation."
  [stage]
  (let [pack (quality-pack stage)]
    (cond
      (nil? pack)                  []
      (malformed-rules? pack)      [(problem stage :quality-pack/malformed (:pack/id pack))]
      (empty? (:pack/rules pack))  [(problem stage :quality-pack/empty (:pack/id pack))]
      :else                        (mapv #(problem stage (rule-problem-kind %) (rule-ref %))
                                         (unresolved-rules pack)))))

(comment
  (declaration-problems {:stage/id (random-uuid)
                         :stage/name "Normalize"
                         :stage/family :normalize}))
