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
(ns ai.miniforge.phase-opsv.interface
  "API surface class 1: internal EDN OPSV application transformations and adapter port."
  (:require
   [ai.miniforge.phase-opsv.actuation :as actuation]
   [ai.miniforge.phase-opsv.boundary.kubernetes-snapshot :as kubernetes-snapshot]
   [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes-client]
   [ai.miniforge.phase-opsv.boundary.kubernetes-discovery :as kubernetes-discovery]
   [ai.miniforge.phase-opsv.boundary.kubernetes-native-discovery :as native-discovery]
   [ai.miniforge.phase-opsv.boundary.kubernetes-membership :as kubernetes-membership]
   [ai.miniforge.phase-opsv.kubernetes-membership-schema :as membership-schema]
   [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory-schema]
   [ai.miniforge.phase-opsv.boundary.kubernetes-partition :as kubernetes-partition]
   [ai.miniforge.phase-opsv.kubernetes-partition-schema :as partition-schema]
   [ai.miniforge.phase-opsv.boundary.kubernetes-volume-projection :as volume-projection]
   [ai.miniforge.phase-opsv.boundary.kubernetes-replicas :as kubernetes-replicas]
   [ai.miniforge.phase-opsv.boundary.kubernetes-reporting :as kubernetes-reporting]
   [ai.miniforge.phase-opsv.kubernetes-verification-schema :as kubernetes-verification-schema]
   [ai.miniforge.phase-opsv.boundary.kubernetes-outside :as kubernetes-outside]
   [ai.miniforge.phase-opsv.boundary.kubernetes-controller-configuration :as controller-configuration]
   [ai.miniforge.phase-opsv.boundary.kubernetes-environment-fingerprint :as native-fingerprint]
   [ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation :as native-preparation]
   [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as native-preparation-schema]
   [ai.miniforge.phase-opsv.boundary.kubernetes-replica-request :as native-replica-request]
   [ai.miniforge.phase-opsv.boundary.experiment-preflight :as experiment-preflight]
   [ai.miniforge.phase-opsv.boundary.environment-comparison :as environment-comparison]
   [ai.miniforge.phase-opsv.environment-comparison-schema :as environment-schema]
   [ai.miniforge.phase-opsv.environment-comparison-request-schema :as environment-request-schema]
   [ai.miniforge.phase-opsv.evidence-recovery :as recovery]
   [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
   [ai.miniforge.phase-opsv.adapter-factory :as adapter-factory]
   [ai.miniforge.phase-opsv.model :as model]
   [ai.miniforge.phase-opsv.boundary.measurement :as measurement]
   [ai.miniforge.phase-opsv.boundary.prometheus-client :as prometheus]
   [ai.miniforge.phase-opsv.boundary.prometheus-criteria :as prometheus-criteria]
   [ai.miniforge.phase-opsv.run-control :as run-control]
   [ai.miniforge.phase-opsv.run-control-state :as control-state]
   [ai.miniforge.phase-opsv.protocol :as protocol]
   [ai.miniforge.phase-opsv.verification-preparation :as preparation]
   [ai.miniforge.phase-opsv.verification-preparation-schema :as preparation-schema]
   [ai.miniforge.phase-opsv.verification-environment :as verification-environment]
   [ai.miniforge.phase-opsv.verification-environment-schema :as verification-environment-schema]
   [ai.miniforge.phase-opsv.verification-run :as verification-run]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OPSVAdapter protocol/OPSVAdapter)

(def ^{:stratum 0} VerificationAdapter protocol/VerificationAdapter)

(def ^{:stratum 0} VerificationPreparationAdapter
  "Read-only target acquisition before verification effects; not admission or authority."
  protocol/VerificationPreparationAdapter)

(def ^{:stratum 0} prepare-verification
  "Invoke read-only preparation. The host must independently admit and retain its result."
  protocol/prepare-verification)

(def ^{:stratum 0} VerificationPreparationReceipt
  "Closed receipt schema for correlated preparation observations, not freshness or membership proof."
  preparation-schema/Receipt)

(def ^{:stratum 0} verification-preparation-receipt
  "Correlate an observation with the request's invocation and candidate/pack hashes.
   Returns a typed receipt or anomaly; acquisition and admission remain caller obligations."
  preparation/receipt)

(def ^{:stratum 0} run-verification protocol/run-verification)

(def ^{:stratum 0} verification-receipt verification-run/receipt)

(def ^{:stratum 0} VerificationEnvironmentDeclaration
  "Opt-in :opsv/verification-environment execution option: exact candidate/pack hashes,
   target and permitted differences. The host supplies the baseline and invocation IDs."
  verification-environment-schema/Declaration)

(def ^{:stratum 0} CandidateVerificationReceipt
  "Closed measurements with target, canonical fingerprint, raw observations and optional replica population."
  verification-environment-schema/Receipt)

(def ^{:stratum 0} candidate-verification-receipt
  "Correlate candidate-aware measurements; no environment admission or execution authority."
  verification-environment/receipt)

(def ^{:stratum 0} VerificationEnvironmentPlan
  "Closed plan schema: binding, baseline, exact image/config changes and optional replica contract.
   Replica contracts declare separate candidate and recovery membership rules and count bounds."
  environment-schema/Plan)

(def ^{:stratum 0} VerificationEnvironmentComparisonRequest
  "Closed comparison request with an independent digest, binding, stage and fingerprint.
   Replica observations are required exactly when the pinned contract declares replica rules."
  environment-request-schema/Request)

(def ^{:stratum 0} pin-verification-environment
  "Bind a canonical baseline and exact image/config changes to one target and attempt.
   Returns an immutable contract and digest, or an invalid-input anomaly.
   The host must retain the digest before effects; this function grants no authority."
  environment-comparison/pin)

(def ^{:stratum 0} compare-verification-environment
  "Compare an observation with an independently retained contract digest and binding.
   Candidate mode applies only exact declared changes; rollback mode uses the baseline.
   Acquisition must independently establish target identity and replica membership.
   Returns the observation unchanged or an anomaly, not a verification or authority grant."
  environment-comparison/compare-observation)

(def ^{:stratum 0} observe-prometheus-window!
  "Read a bounded window from an explicit Prometheus endpoint without mutation authority."
  prometheus/observe-window!)

(def ^{:stratum 0} observe-prometheus-criteria!
  "Read declared criterion windows, requiring cluster/namespace/service label correlation.
   Configuration supplies :measurement/prometheus, :measurement/target, :measurement/service,
   :measurement/label-names and :measurement/criteria (id, request, reduction).
   All request budgets must fit the resolved endpoint cap before collection starts.
   Explicit :maximum, :minimum or :last reductions produce :observations by criterion ID;
   :measurement/windows retains declarations and original query/window/labels/samples.
   :measurement/declaration retains the declared target/service/label names, never credentials.
   Original windows may be sensitive; protect copies before N6 publication.
   An anomaly prevents partial results. Each read is bounded, not an atomic batch.
   No target UID attestation, confidence, artifact IDs, retention, experiments or verification verdict."
  prometheus-criteria/observe!)

(def ^{:stratum 0} KubernetesDeploymentMembershipObservation
  "Closed captured shape for a declared target and native namespace/controller/Pod observations.
   Schema validation alone does not admit controller membership."
  membership-schema/Observation)

(def ^{:stratum 0} KubernetesDeploymentInventoryTarget
  "Explicit runtime target and Deployment name for a complete namespace inventory."
  inventory-schema/Target)

(def ^{:stratum 0} KubernetesDeploymentInventoryObservation
  "Complete raw namespace inventories, not an admitted member population or verification verdict."
  inventory-schema/Observation)

(def ^{:stratum 0} observe-kubernetes-deployment-inventory!
  "Read a bounded complete namespace Pod/ReplicaSet inventory after matching root UIDs.
   Sequential reads recheck root UIDs before returning; this is not an atomic snapshot.
   Takes explicit namespace-inventory settings and target declaration; selector overrides fail.
   No membership, configuration, health or execution authority is established."
  kubernetes-client/observe-inventory!)

(def ^{:stratum 0} admit-kubernetes-deployment-membership
  "Admit captured Pod-to-ReplicaSet-to-Deployment controller identities by UID.
   Returns the original observation or an anomaly. This proves no freshness,
   template/configuration match, replica bounds, stability or execution authority."
  kubernetes-membership/admit)

(def ^{:stratum 0} KubernetesDeploymentInventoryPartition
  "Raw complete inventory and its derived controller-membership view; schema alone is not proof."
  partition-schema/Partition)

(def ^{:stratum 0} partition-kubernetes-deployment-inventory
  "Prove captured Deployment membership while retaining the complete raw inventory unchanged.
   Outside observations remain available for independent drift comparison, never ignored.
   Returns a partition or anomaly; establishes no freshness, configuration, health or authority."
  kubernetes-partition/admit)

(def ^{:stratum 0} project-kubernetes-pod-volume-bindings
  "Project one recognized generated service-account volume binding into a versioned semantic view.
   All volume content, ordinary names and other PodSpec fields remain intact.
   Hash the complete versioned representation; retain raw observations separately.
   Returns a representation or anomaly, never a native manifest, full template match or execution authority."
  volume-projection/project)

(def ^{:stratum 0} project-kubernetes-deployment-replicas
  "Derive replica identities, configuration hashes, pools and runtime image digests from captured data.
   Preserve raw input and versioned per-Pod configuration projections. Placement uses captured Nodes.
   Pending or incomplete selected Pods cannot prove runtime observations; empty populations remain explicit.
   This proves no freshness, approved template match, health, outside-resource stability or execution authority."
  kubernetes-replicas/project)

(def ^{:stratum 0} observe-kubernetes-verification!
  "Capture complete namespace workload/Node inventories and current/desired configuration dependencies.
   Use explicit settings, one deadline and a shared object budget; recheck declared roots after all reads.
   Raw values may be sensitive: compare/hash originals before protecting reporting copies for storage.
   This read-only, bounded multi-read is not atomic, a verification verdict or execution authority."
  kubernetes-client/observe-verification!)

(def ^{:stratum 0} KubernetesVerificationPreparationDeclaration
  "Independent workflow identity and native Deployment target for a preparation callback."
  native-preparation-schema/Declaration)

(def ^{:stratum 0} prepare-kubernetes-replica-request
  "Prepare a Deployment-only JSON Patch from declaration, native preparation request,
   original baseline receipt, fresh native capture and closed trial/restoration parameters.
   Trials supply :replicas/count; restoration derives the original desired count.
   Return canonical request, payload hash and exact resource reference, or an anomaly.
   UID/revision tests and declared bounds constrain the data, not execution authority.
   No provider call, retention/ownership proof, N4/N8 admission or verified recovery."
  native-replica-request/prepare)

(def ^{:stratum 0} evaluate-experiment-preflight
  "Evaluate temporary staging N4 gates from trusted runtime opt-in and prepared baseline.
   Accept a workflow context, canonical native preparation request and correlated receipt.
   Return experiment-specific checks, envelope and comparison binding, or an anomaly.
   Does not verify retention/ownership, issue grants, check N8, or authorize a provider;
   the runtime must compose those obligations and reevaluate at each commit."
  experiment-preflight/evaluate)

(def ^{:stratum 0} prepare-kubernetes-verification!
  "Correlate the host request and pin before bounded read-only capture and canonical projection.
   Declaration binds workflow, runtime target and Deployment name.
   Requires explicit candidate/recovery membership; bounds come from recovery.
   Returns original sensitive observations for host comparison and protected N6 retention.
   No storage, candidate/load effects, baseline adoption, health verdict or execution authority."
  native-preparation/prepare!)

(def ^{:stratum 0} KubernetesVerificationObservation
  "Raw bounded verification-capture shape, not a membership, health or execution proof."
  kubernetes-verification-schema/Observation)

(def ^{:stratum 0} prepare-kubernetes-reporting
  "Prepare data-minimized native reporting through the shared N6 scan/redaction policy, before storage.
   Compute comparison hashes from originals first. This copy is not a manifest, comparison or recovery contract.
   Preserve native identity/provenance and typed desired counts; raw specs/status and semantic payloads are confidential.
   Returns protected artifact content/compliance metadata or a value-free anomaly; no storage or authority."
  kubernetes-reporting/prepare)

(def ^{:stratum 0} project-kubernetes-outside-invariants
  "Hash captured resources outside the natively proven Deployment/ReplicaSet/Pod population.
   Retain source and versioned projection, including controller links, lifecycle and runtime identities.
   This does not check owned controller configuration/counts, replica invariants, freshness or health.
   Protect sensitive reporting copies only after hashing; no storage, I/O or execution authority."
  kubernetes-outside/project)

(def ^{:stratum 0} project-kubernetes-controller-configuration
  "Retain owned controller desired configuration after checking Deployment and aggregate ReplicaSet counts.
   Only replicas is removed from each controller spec; original counts/source and ownership remain visible.
   Callers must derive bounds from a retained contract matching this target and attempt before effects.
   This pure projection does not prove that predeclaration, replica runtime invariants, freshness or health."
  controller-configuration/project)

(def ^{:stratum 0} project-kubernetes-environment-fingerprint
  "Compose a complete native capture and independently predeclared count bounds into canonical observed values.
   Preserve raw source, semantic projection, controller counts and separate replica observations.
   Require nonempty homogeneous observed runtime; never fill missing images from expectations.
   Callers must bind bounds to the retained target/attempt contract before effects and compare the result.
   No freshness, health, population verdict, storage, I/O or execution authority. Protect copies before reporting."
  native-fingerprint/project)

(def ^{:stratum 0} decode-measurement-window
  "Decode one complete Prometheus float series against a declared bounded grid.
   Observations preserve query provenance; they confer no verification or authority."
  measurement/decode-window)

(def ^{:stratum 0} discover-signals protocol/discover-signals)

(def ^{:stratum 0} run-guarded-ramp protocol/run-guarded-ramp)

(defn ^{:stratum 0} create-run-supervisor
  "Create a host-owned OPSV stop domain. Stopping it cannot be reversed."
  [] (control-state/create))

(defn ^{:stratum 0} register-run-control!
  "Register a run before execution. Returns runtime control/fence handles or an anomaly.
   request-abort! must acknowledge scheduling a safe-boundary load abort with true."
  [supervisor workflow-id authority-directory request-abort!]
  (run-control/register! supervisor workflow-id authority-directory request-abort!))

(defn ^{:stratum 0} stop-supervised-runs!
  "Close shared mutation admission first, then request aborts and revoke tracked grants.
   Reports incomplete cleanup; admitted mutations may still settle or remain unknown."
  [supervisor now] (run-control/stop! supervisor now))

(defn ^{:stratum 0} retire-run-control!
  "Stop and clean up a completed run. Retain it while cleanup or admitted effects remain."
  [handle now] (run-control/retire! handle now))

(def ^{:stratum 0} phase-keys
  [:opsv/discover :opsv/plan :opsv/execute :opsv/converge
   :opsv/synthesize :opsv/verify :opsv/actuate])

(def ^{:stratum 0} discover model/discover)

(def ^{:stratum 0} plan model/plan)

(def ^{:stratum 0} execute model/execute)

(def ^{:stratum 0} converge model/converge)

(def ^{:stratum 0} synthesize model/synthesize)

(def ^{:stratum 0} verify model/verify)

(defn ^{:stratum 0} publish-finalized-evidence!
  "Resume retained post-actuation evidence or finalized N6 publication; never actuates."
  [ctx]
  (finalization/publish-finalized! ctx))

(defn ^{:stratum 0} recover-actuation-evidence!
  "Restore a failed actuation checkpoint and retry only its evidence operations.
   Returns an updated context with the phase still failed, or an input anomaly.
   The caller must persist the returned context. Never invokes the provider."
  [ctx]
  (recovery/recover-with-exception-handling! ctx))

(defn ^{:stratum 0} actuate
  [ctx]
  (actuation/actuate
   ctx (get-in ctx [:execution/phase-results :opsv/verify :result :output])))

(defn ^{:stratum 0} functional-adapter
  "Build an adapter with explicit discovery, ramp, optional preparation and verification callbacks.
   Two/three retain their meaning; four add a separate read-only preparation callback.
   The host owns invocation order; missing callbacks are unavailable, never fabricated."
  ([discover-fn guarded-ramp-fn]
   (adapter-factory/create discover-fn guarded-ramp-fn))
  ([discover-fn guarded-ramp-fn verify-fn]
   (adapter-factory/create discover-fn guarded-ramp-fn verify-fn))
  ([discover-fn guarded-ramp-fn prepare-fn verify-fn]
   (adapter-factory/create discover-fn guarded-ramp-fn prepare-fn verify-fn)))

(def ^{:stratum 0} decode-kubernetes-fingerprint
  "Decode one explicitly captured target into drift-sensitive evidence.
   Acquisition owns target selection and configuration-reference closure.
   This is neither a live capture nor a freshness, verification or authority proof."
  kubernetes-snapshot/decode)

(def ^{:stratum 0} observe-kubernetes-fingerprint!
  "Read one explicit target and its ConfigMap/Secret dependencies within declared bounds.
   This is a bounded multi-read capture, not an atomic cluster snapshot or authority grant."
  kubernetes-client/observe-fingerprint!)

(def ^{:stratum 0} discover-kubernetes-signals!
  "Acquire an explicit Kubernetes target and label-correlated CPU/backlog windows.
   Queries and label names are operator configuration. Each read is bounded;
   this neither runs load nor verifies a candidate or grants execution authority."
  kubernetes-discovery/discover!)

(def ^{:stratum 0} discover-kubernetes-verification-signals!
  "Acquire complete native target observations and label-correlated CPU/backlog windows.
   Extend discovery settings with :discovery/declaration and :discovery/replica-bounds.
   Return :discovery/kubernetes-verification (canonical projection and original
   sensitive observations) and :discovery/candidate-drivers, or an anomaly.
   These originals are not a report-safe copy.
   No experiment, mutation, baseline retention, verification verdict or execution authority."
  native-discovery/discover!)

(comment
  (functional-adapter identity identity))
