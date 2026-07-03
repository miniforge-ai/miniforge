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

(ns ai.miniforge.dag-executor.plan-security
  "Execution-plan security gate — the runtime enforcement of the capsule
   isolation policies (standards `foundations/runtime-*`). These policies check
   execution-plan DATA (mounts, image digest, runtime capabilities), so they
   cannot be enforced by the changed-files policy pack; this gate runs against a
   built plan before it is handed to the runtime.

   Each policy keeps its own designed action:
   - no-host-docker-socket    -> :hard-stop (no opt-out; catastrophic escape)
   - require-image-digest-pin -> :hard-stop (no reproducibility without it, N6)
   - restrict-host-mounts     -> :review    (human decision, allowlist captures
                                             the recurring cases)
   - require-rootless         -> :warn in OSS, :hard-stop in Fleet (a hard-stop
                                             on Docker would break the OSS setup)

   `check-plan-security` reduces the findings to one outcome: a forbidden
   anomaly when any hard-stop fires, otherwise a success carrying any review /
   warning findings for the caller to surface."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

;; --- Security invariants (never operator-tunable) -------------------------

(def ^:private runtime-socket-suffixes
  "Host-path suffixes that identify a container-runtime control socket.
   Bind-mounting any of these hands the capsule the host daemon — the single
   most catastrophic isolation failure (runtime-no-host-docker-socket). There is
   no safe reason to allow one, so this is a code invariant, not config."
  #{"docker.sock" "podman.sock" "containerd.sock"})

(def ^:private image-digest-pattern
  "The only acceptable `:image-digest` value shape: a content-addressed sha256
   digest (runtime-require-image-digest-pin, per N6). A tag or bare reference is
   mutable and has no reproducibility story. Fixed by the digest format, not
   operator-tunable."
  #"^sha256:[a-f0-9]{64}$")

;; --- Deployment-tunable defaults (config-as-data fallbacks) ---------------

(def default-rootless-action
  "Enforcement action for a runtime lacking the :rootless capability when the
   deployment's security config is silent. OSS default is :warn — Podman is
   rootless but Docker is not, and hard-stopping Docker would break the default
   OSS posture. Fleet deployments override this to :hard-stop via config."
  :warn)

;; --- Detection predicates (pure) ------------------------------------------

(defn- host-socket-mount?
  "True when `mount`'s host path is a container-runtime control socket."
  [mount]
  (let [host-path (:host-path mount)]
    (boolean (and host-path
                  (some #(str/ends-with? host-path %) runtime-socket-suffixes)))))

(defn- valid-image-digest?
  "True when `image-digest` is a bare sha256:<64 hex> digest."
  [image-digest]
  (boolean (and (string? image-digest)
                (re-matches image-digest-pattern image-digest))))

(defn- allowlisted-host-path?
  "True when `host-path` is on the deployment's mount allowlist."
  [allowlist host-path]
  (contains? (set allowlist) host-path))

(defn- finding
  "A single policy finding — the rule that fired, its designed action, an
   audit-facing message, and the offending detail."
  [rule action message detail]
  {:security/rule rule :security/action action
   :security/message message :security/detail detail})

;; --- Scan (plan -> findings) ----------------------------------------------

(defn scan-plan
  "Return the seq of security findings for `plan`, given the runtime
   `descriptor` and `security-config`
   {:host-path-allowlist [...] :rootless-action :warn|:hard-stop}.

   A host-socket mount is reported once as a :hard-stop and is not also raised
   as a host-mount review — the hard-stop already blocks it."
  [plan descriptor {:keys [host-path-allowlist rootless-action]
                    :or   {rootless-action default-rootless-action}}]
  (let [mounts          (get plan :mounts [])
        {socket-mounts true other-mounts false} (group-by host-socket-mount? mounts)
        socket-findings (map (fn [m]
                               (finding :std/runtime-no-host-docker-socket :hard-stop
                                        "Host runtime-socket mount is forbidden — grants the capsule full host-daemon control."
                                        {:host-path (:host-path m)}))
                             socket-mounts)
        digest-findings (when-not (valid-image-digest? (:image-digest plan))
                          [(finding :std/runtime-require-image-digest-pin :hard-stop
                                    "Execution plan :image-digest must be a content-addressed sha256:<64 hex> digest."
                                    {:image-digest (:image-digest plan)})])
        mount-findings  (->> other-mounts
                             (remove #(allowlisted-host-path? host-path-allowlist (:host-path %)))
                             (map (fn [m]
                                    (finding :std/runtime-restrict-host-mounts :review
                                             "Host mount outside the allowlist — needs human review before the capsule runs."
                                             {:host-path (:host-path m)}))))
        rootless-finding (when-not (descriptor/capable? descriptor :rootless)
                           [(finding :std/runtime-require-rootless rootless-action
                                     "Runtime does not advertise the :rootless capability."
                                     {:runtime/capabilities (descriptor/capabilities descriptor)})])]
    (concat socket-findings digest-findings mount-findings rootless-finding)))

;; --- Decide (findings -> outcome) -----------------------------------------

(defn check-plan-security
  "Evaluate `plan` against the runtime capsule-isolation policies and reduce the
   findings to a single outcome:
   - any :hard-stop finding  -> a :anomalies/forbidden anomaly carrying every
                                hard-stop finding (the plan MUST NOT run)
   - otherwise               -> a success whose output is
                                {:security/decision :review|:pass
                                 :security/review [...] :security/warnings [...]}.

   The caller rejects the plan on a failed? result, surfaces :security/review
   items for human approval, and logs :security/warnings."
  [plan descriptor security-config]
  (let [findings   (scan-plan plan descriptor security-config)
        by-action  (group-by :security/action findings)
        reviews    (get by-action :review [])
        warnings   (get by-action :warn [])
        ;; Fail closed: any finding whose action is not explicitly :review or
        ;; :warn — e.g. a :rootless-action misconfigured to an unknown keyword —
        ;; is treated as a hard-stop rather than silently dropped. A security
        ;; gate must never weaken enforcement on unexpected input.
        hard-stops (into [] (remove (comp #{:review :warn} :security/action)) findings)]
    (if (seq hard-stops)
      (response/make-anomaly :anomalies/forbidden
                             "Execution plan violates a hard-stop capsule-isolation policy"
                             {:security/findings hard-stops})
      (response/success {:security/decision (if (seq reviews) :review :pass)
                         :security/review   reviews
                         :security/warnings warnings}))))
