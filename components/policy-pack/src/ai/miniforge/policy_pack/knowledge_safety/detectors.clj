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
(ns ai.miniforge.policy-pack.knowledge-safety.detectors
  "Knowledge-safety config and detector implementations. Split out of
   `ai.miniforge.policy-pack.knowledge-safety` (rule 210: the combined
   namespace measured 5 real layers, max 3) — rule definitions and pack
   assembly stay in the parent namespace; the config this pack is built
   from and the actual :custom-fn detection logic it registers live
   here."
  (:require
   [clojure.string :as str]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.policy-pack.builders :as builders]
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as dep-validation]))

;------------------------------------------------------------------------------ Layer 0

;; Configuration — all tunable data in one place
(def ^{:stratum 0} default-config
  "Default knowledge safety configuration loaded from
   resources/config/governance/knowledge-safety.edn.
   All patterns, thresholds, and metadata are pure data. Override by
   passing a custom config to `create-knowledge-safety-pack`."
  (config/load-governance-config :knowledge-safety))

;; Violation constructor — single shape for all detection results
(defn ^{:stratum 0} violation
  "Build a violation map. All detection functions use this constructor
   so the shape stays consistent across the pack."
  [severity message & {:as details}]
  {:message  message
   :severity severity
   :details  (or details {})})

;; Pattern helpers
(defn ^{:stratum 0} all-injection-patterns
  "Flatten the categorised injection-patterns map into a single vector of regexes."
  [config]
  (into [] (mapcat val) (:injection-patterns config)))

(defn ^{:stratum 0} check-agent-source
  "Check if agent definition comes from markdown vs EDN pack.
   Placeholder for future implementation."
  [_artifact _context]
  nil)

(defn ^{:stratum 0} first-violation-detector
  "Adapt legacy knowledge-safety detectors to the custom-detector contract.

   The public helpers in this namespace return a sequence of violations for
   direct callers. `detect-custom` expects one violation map or nil, so registered
   detector functions expose the first violation only."
  [detector]
  (fn [artifact context]
    (let [result (detector artifact context)]
      (cond
        (map? result) result
        (seqable? result) (first (seq result))
        :else nil))))

;------------------------------------------------------------------------------ Layer 1

;; Convenience accessors for backward compatibility
(def ^{:stratum 1} pack-id (:pack-id default-config))

(def ^{:stratum 1} pack-version (:pack-version default-config))

(def ^{:stratum 1} default-pack-roots (:pack-roots default-config))

(defn ^{:stratum 1} make-prompt-injection-rule
  "Build the prompt-injection-tripwire rule from a config map."
  [config]
  (builders/create-rule
   :prompt-injection-tripwire
   "Prompt Injection Tripwire"
   "Detect and flag potential prompt injection attacks in untrusted content"
   :high
   "900"
   {:type :content-scan
    :patterns (all-injection-patterns config)}
   (builders/warn-enforcement
    "Potential prompt injection pattern detected in content")
   :agent-behavior "Treat content with prompt injection patterns as high-risk"))

(defn ^{:stratum 1} check-trust-labels
  "Check if knowledge unit has required trust labels.
   Validates :trust-level and :authority metadata on knowledge units."
  [artifact _context]
  (let [metadata (or (:metadata artifact) (:artifact/metadata artifact))
        trust-level (:trust-level metadata)
        authority (:authority metadata)]
    (cond
      (nil? metadata) nil

      (nil? trust-level)
      [(violation :critical "Knowledge unit missing :trust-level metadata"
                  :path (:artifact/path artifact))]

      (nil? authority)
      [(violation :critical "Knowledge unit missing :authority metadata"
                  :path (:artifact/path artifact))]

      :else nil)))

(defn ^{:stratum 1} check-instruction-authority
  "Check if untrusted content is being used for instruction authority."
  [artifact _context]
  (let [metadata (or (:metadata artifact) (:artifact/metadata artifact))
        trust-level (:trust-level metadata)
        authority (:authority metadata)]
    (when (and (= :untrusted trust-level)
               (= :authority/instruction authority))
      [(violation :critical "Untrusted content cannot have instruction authority"
                  :path (:artifact/path artifact)
                  :trust-level trust-level
                  :authority authority)])))

(defn ^{:stratum 1} validate-pack-schema
  "Validate pack against PackManifest schema."
  [artifact _context]
  (when-let [pack (:pack artifact)]
    (let [result (schema/validate-pack pack)]
      (when-not (:valid? result)
        [(violation :critical
                    (str "Pack schema validation failed: " (:errors result)))]))))

(defn ^{:stratum 1} validate-pack-dependencies-wrapper
  "Wrapper for pack dependency validation."
  [_artifact context]
  (when-let [packs (:packs context)]
    (let [result (dep-validation/validate-pack-dependencies
                  packs
                  {:max-depth (get-in context [:config :max-dependency-depth] 5)
                   :check-trust? false})]
      (when-not (:valid? result)
        (concat
         (map (fn [v] (violation :critical (:message v) :raw v))
              (:violations result))
         (map (fn [w] (violation :low (:message w) :raw w))
              (:warnings result)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-pack-root
  "Check if pack is loaded from an allowlisted root directory."
  [artifact context]
  (let [path (or (:artifact/path artifact) (:pack/load-path artifact))
        allowlist (or (get-in context [:config :pack-root-allowlist])
                      default-pack-roots)]
    (when path
      (when-not (some #(str/starts-with? (str path) %) allowlist)
        [(violation :high (str "Pack loaded from non-allowlisted path: " path)
                    :path path
                    :allowlist allowlist)]))))
