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

(ns ai.miniforge.adapter-claude-code.tool-profiles
  "Tool profile contributions for the Claude CLI native toolset.

   Registers determinism + anomaly-category data for the eight tools
   the Claude CLI ships natively (Read, Bash, Grep, Glob, Edit, Write,
   WebSearch, WebFetch) so progress-detector's tool-loop detector
   knows which fingerprint repeats are mechanical-eligible vs
   heuristic-only.

   Architectural intent: progress-detector ships zero hardcoded tool
   data — components that vendor a tool contribute their own profile.
   This namespace is the contribution from the Claude CLI adapter.

   Registration happens at namespace load via a defonce side-effect.
   `register-profiles!` is idempotent (it overwrites by :tool/id) so
   re-loading or explicit re-invocation is safe."
  (:require
   [ai.miniforge.progress-detector.interface :as pd]))

;------------------------------------------------------------------------------ Layer 0
;; Profile data

(def claude-cli-profiles
  "Profile entries for the eight Claude CLI native tools.

   :determinism choices, per the Stage 1 spec's tool capability registry:

     :stable-with-resource-version - mechanical when the resource
       hash matches across calls (Read of an unchanged file, or
       Edit/Write that produces a hash-equal post-state — i.e. a
       no-op patch — counts as a loop).
     :stable-ish - mostly-deterministic, mechanical with bounded
       false-positives (same Grep pattern, same Glob input).
     :environment-dependent - mechanical only when the parsed
       failure fingerprint matches across calls (Bash with the
       same command failing the same way is a loop; Bash with
       different exit codes or different parsed errors is not).
     :unstable - heuristic only (network calls return different
       payloads even for identical inputs)."
  [{:tool/id            :tool/Read
    :determinism        :stable-with-resource-version
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/Bash
    :determinism        :environment-dependent
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/Grep
    :determinism        :stable-ish
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/Glob
    :determinism        :stable-ish
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/Edit
    :determinism        :stable-with-resource-version
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/Write
    :determinism        :stable-with-resource-version
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/WebSearch
    :determinism        :unstable
    :anomaly/categories #{:anomalies.agent/tool-loop}}
   {:tool/id            :tool/WebFetch
    :determinism        :unstable
    :anomaly/categories #{:anomalies.agent/tool-loop}}])

;------------------------------------------------------------------------------ Layer 1
;; Registration

(defn register-profiles!
  "Register every profile in `claude-cli-profiles` against `registry`.

   Idempotent: each entry overwrites by :tool/id, so calling twice
   leaves the registry shape unchanged.

   Arguments:
     registry - registry atom (defaults to pd/default-registry)

   Returns: the final registry map."
  ([]
   (register-profiles! pd/default-tool-registry))
  ([registry]
   (last (mapv #(pd/register-tool-profile! registry %) claude-cli-profiles))))

;------------------------------------------------------------------------------ Layer 2
;; Load-time contribution

(defonce ^:private registered?
  ;; Side-effect at namespace load: contribute the eight profiles to
  ;; the process-wide registry. defonce makes it a one-shot;
  ;; register-profiles! itself is idempotent so re-invocation in
  ;; tests is safe.
  (do (register-profiles!) true))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Inspect the registered profiles
  (require '[ai.miniforge.progress-detector.interface :as pd])
  (pd/all-tool-ids)
  ;; => [:tool/Bash :tool/Edit :tool/Glob :tool/Grep :tool/Read
  ;;     :tool/WebFetch :tool/WebSearch :tool/Write]

  (pd/tool-determinism :tool/Read)        ; => :stable-with-resource-version
  (pd/tool-determinism :tool/Bash)        ; => :environment-dependent
  (pd/tool-determinism :tool/UnknownTool) ; => :unstable (registry default)

  ;; Re-registering is idempotent
  (let [reg (pd/make-tool-registry)]
    (register-profiles! reg)
    (register-profiles! reg)
    (count @reg))
  ;; => 8

  :leave-this-here)
