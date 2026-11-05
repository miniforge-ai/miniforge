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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.launch
  "Launch plan and capsule-isolation security gate for the OCI-CLI executor."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.execution-plan :as execution-plan]
   [ai.miniforge.dag-executor.plan-security :as plan-security]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.env
    :refer [agent-credential-names filter-env-by-trust normalize-env refused-env-name?
            untrusted-env-blanks]]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Configuration
;; ============================================================================
(def ^{:stratum 0} default-workdir "/workspace")

(defn ^{:stratum 0} build-launch-plan
  "Assemble the execution plan for a pending launch from what the launch site
   actually knows: the resolved image digest, the caller's mounts / env /
   trust level from env-config, and the capsule's keep-alive command. Optional
   fields (:time-limit-ms :memory-limit-mb :network-profile :secrets-refs)
   pass through only when env-config carries them — absent fields stay absent
   rather than being filled with guesses. The :env keys are normalized to
   strings so the plan conforms to ExecutionPlanSchema, and secrets are
   dropped for an :untrusted plan (`filter-env-by-trust`). A nil or unknown
   :trust-level becomes :untrusted, so neither the secret filter nor the
   read-only mount rule can be skipped by a malformed value. The filter keeps
   the agent's credentials: `agent-credential-names` and those of the agent
   backend, env-config's :agent-backend or else the configured [:llm :backend],
   plus the names env-config's :env-passthrough lists. An :untrusted plan
   also sets `untrusted-env-blanks` for `image-env`, the image's ENV as
   {name value}, under any value env-config gives.

   This one plan is both what `check-plan-security` inspects and what
   `create-container` receives as :execution-plan, so the plan the gate
   checked is the plan that runs."
  ([image-digest env-config] (build-launch-plan image-digest env-config {}))
  ([image-digest env-config image-env]
   (let [trust-level (get execution-plan/trust-levels (:trust-level env-config) :untrusted)
         ;; `or`, not a get default: the user config is read only when
         ;; env-config names no backend.
         backend     (or (:agent-backend env-config)
                         (get-in (config/load-merged-config) [:llm :backend]))
         keep-names  (-> agent-credential-names
                         (into (llm/backend-credential-env backend))
                         (into (map name) (:env-passthrough env-config)))
         env         (filter-env-by-trust (normalize-env (get env-config :env {}))
                                          trust-level keep-names)
         plan-env    (cond->> env
                       (= :untrusted trust-level)
                       (merge (untrusted-env-blanks image-env keep-names)))]
     (cond-> {:image-digest image-digest
              :command      ["sleep" "infinity"]
              :mounts       (get env-config :mounts [])
              :env          plan-env
              :trust-level  trust-level}
       (:time-limit-ms env-config)   (assoc :time-limit-ms (:time-limit-ms env-config))
       (:memory-limit-mb env-config) (assoc :memory-limit-mb (:memory-limit-mb env-config))
       (:network-profile env-config) (assoc :network-profile (:network-profile env-config))
       (:secrets-refs env-config)    (assoc :secrets-refs (:secrets-refs env-config))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} security-gate-check
  "Run the capsule-isolation security gate for a pending launch. Resolves the
   image's content-addressed digest via `digest-fn` (injected for testing),
   builds the launch plan the gate inspects via `build-launch-plan`, and
   applies `plan-security/check-plan-security`. The host-path allowlist is the
   sandbox workdir (the only host path a well-formed plan mounts today).

   Returns `result/ok` carrying the gate decision when the launch may proceed
   (a passing gate may still carry non-blocking :security/warnings — e.g. a
   non-rootless runtime in OSS), or `result/err :security-policy-violation`
   with the findings on a hard-stop. A nil digest fails closed (hard-stop),
   since a plan with no pinned digest cannot satisfy require-image-digest-pin.

   A passing result also carries the checked `:launch-plan`;
   `acquire-environment!` creates the container FROM that plan — its digest as
   the immutable image reference, the plan itself as create-container's
   :execution-plan.

   A plan env name `refused-env-name?` flags is a hard-stop at every
   trust level: such a name is not one env-file assignment, and Podman
   reads `*` or a trailing `*` in `-e NAME` as a pattern that copies every
   matching variable of its own environment into the container.

   `image-env-fn` (injected) gives the pinned image's ENV, whose secrets an
   :untrusted plan blanks (`untrusted-env-blanks`). An :untrusted launch
   whose image ENV cannot be read fails closed."
  [descriptor image env-config digest-fn image-env-fn]
  (let [digest          (digest-fn descriptor image)
        image-env       (when digest (image-env-fn descriptor digest))
        plan            (build-launch-plan digest env-config image-env)
        security-config {:host-path-allowlist #{(get env-config :workdir default-workdir)}
                         :rootless-action     plan-security/default-rootless-action}
        decision        (plan-security/check-plan-security plan descriptor security-config)
        bad-names       (filter refused-env-name? (keys (:env plan)))]
    (cond
      (response/anomaly-map? decision)
      (result/err :security-policy-violation
                  (:anomaly/message decision)
                  {:security/findings (:security/findings decision)})

      (seq bad-names)
      (result/err :security-policy-violation
                  (messages/t :oci/env-name-refused {:names (str/join ", " (map pr-str bad-names))}))

      (and (= :untrusted (:trust-level plan)) (nil? image-env))
      (result/err :security-policy-violation (messages/t :oci/image-env-unreadable {:image image}))

      :else
      (result/ok (assoc (:output decision) :launch-plan plan)))))
