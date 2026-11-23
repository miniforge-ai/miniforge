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
(ns ai.miniforge.deliberation-workspace.provenance
  "Attestation stages (N14 §2.4, §3.4, §8): provenance a proposal claims —
   a hard constraint's source, execution or user evidence, an experiment
   result, an artifact export — must match what the runtime recorded, never
   what the model asserts."
  (:require
   [ai.miniforge.deliberation-workspace.messages :as messages]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} attested-source-classes
  "Evidence classes that satisfy acceptance (N14 §2.4) and so must match a
   runtime receipt rather than a model assertion."
  #{:execution :user})

(def ^{:stratum 0} artifact-ref-attributes
  "The artifact-ref attributes that must equal the recorded capsule export,
   so a reference names exactly what the runtime exported (N14 §8)."
  [:capsule-id :digest :paths])

(def ^{:stratum 0} execution-evidence-sources
  "Roles that may attach `:execution` evidence (N14 §5.3); `:user` acts
   through OCI (§10.2)."
  #{:verifier :user})

(defn- ^{:stratum 0} execution-class? [source-class]
  (= :execution source-class))

(defn- ^{:stratum 0} receipt-matches?
  "True when `receipt` attests `source-class` and exactly `statement`."
  [receipt source-class statement]
  (and (= source-class (:source-class receipt))
       (= statement (:statement receipt))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} hard-constraint-source?
  "True when `role` may create a `:hard` constraint citing `source-ref`:
   the user through OCI, or the interpreter citing a recorded spec source
   (N14 §2.4)."
  [workspace role source-ref]
  (or (= :user role)
      (and (= :interpreter role)
           (validation/text? source-ref)
           (contains? (get workspace :workspace/spec-sources #{}) source-ref))))

(defn- ^{:stratum 1} unattested-spec?
  "True when `spec` claims provenance the runtime has not attested: a hard
   constraint from an unentitled source, or execution/user evidence without
   a matching receipt."
  [workspace role spec]
  (let [{:keys [source-class receipt kind source-ref]} (get spec :attrs {})
        attestation (get-in workspace [:workspace/receipts receipt])]
    (or (and (= :hard kind)
             (not (hard-constraint-source? workspace role source-ref)))
        (and (contains? attested-source-classes source-class)
             (not (receipt-matches? attestation source-class (:statement spec)))))))

(defn- ^{:stratum 1} attested-execution?
  "True when `id` names evidence backed by a runtime execution receipt."
  [workspace id]
  (let [evidence (get (validation/objects-of workspace) id)
        receipt (get-in workspace [:workspace/receipts
                                   (get-in evidence [:object/attrs :receipt])])]
    (and (= :evidence (:object/type evidence))
         (execution-class? (get-in evidence [:object/attrs :source-class]))
         (receipt-matches? receipt :execution (:object/statement evidence)))))

;; True when `operation` creates `:execution` evidence or attaches any: at
;; either end of an edge of its own `:links` (commit writes those onto its
;; targets) or a created object's, through its `:discriminates` (persisted as
;; links on what it creates), or as cited `:evidence` (a challenge attaches what
;; it cites, N14 §3.5). Only its producers may make such evidence bear on a
;; record (§5.3).
(defn- ^{:stratum 1} attaches-execution?
  [workspace operation]
  (let [objects (validation/objects-of workspace)
        created (validation/created-specs operation)
        linked (concat (get operation :discriminates []) (get operation :evidence [])
                       (when (seq (get operation :links)) (get operation :targets []))
                       (mapcat #(mapcat val (get % :links {})) (cons operation created)))]
    (or (some #(execution-class? (get-in % [:attrs :source-class])) created)
        (some #(execution-class? (get-in objects [% :object/attrs :source-class])) linked))))

(defn- ^{:stratum 1} check-provenance [workspace operation {:keys [role]}]
  (when (or (some #(unattested-spec? workspace role %) (validation/created-specs operation))
            (and (attaches-execution? workspace operation)
                 (not (contains? execution-evidence-sources role))))
    (validation/reject-operation :anomalies.deliberation/unattested-provenance
                                 (messages/system-t :anomaly/unattested-provenance)
                                 operation)))

(defn- ^{:stratum 1} check-evidence-links
  "An attachment names every evidence object it creates or references under
   `:supports` — the edge that links evidence to its targets — and every
   `:supports` destination is evidence."
  [workspace operation _context]
  (when (= :attach-evidence (:op operation))
    (let [supports (get-in operation [:links :supports] [])
          created (into {} (map (juxt :id :type)) (validation/created-specs operation))
          type-of #(or (:object/type (get (validation/objects-of workspace) %)) (get created %))]
      (when (or (empty? supports)
                (not-every? (set supports) (concat (keys created) (get operation :evidence [])))
                (some #(not= :evidence (type-of %)) supports))
        (validation/reject-operation :anomalies.deliberation/evidence-link
                                     (messages/system-t :anomaly/evidence-link) operation)))))

;; A result cites attested execution evidence that every target experiment
;; links under `:supports`, already or through this operation's `:links`, so
;; the completed experiment keeps its observation.
(defn- ^{:stratum 1} check-experiment-result [workspace operation _context]
  (let [linked (set (get-in operation [:links :supports] []))
        supported? (fn [id target]
                     (or (contains? linked id)
                         (contains? (object/linked (get (validation/objects-of workspace) target) :supports) id)))
        backs? (fn [id] (and (attested-execution? workspace id)
                             (every? #(supported? id %) (get operation :targets []))))]
    (when (and (= :record-experiment-result (:op operation))
               (not (some backs? (get operation :evidence []))))
      (validation/reject-operation :anomalies.deliberation/unbacked-result
                                   (messages/system-t :anomaly/unbacked-result) operation))))

(defn- ^{:stratum 1} check-artifact-export
  "An artifact reference names a capsule export the runtime recorded."
  [workspace operation _context]
  (when (some #(and (= :artifact-ref (:type %))
                    (not= (select-keys (:attrs %) artifact-ref-attributes)
                          (get-in workspace [:workspace/artifact-exports
                                             (get-in % [:attrs :digest])])))
              (validation/created-specs operation))
    (validation/reject-operation :anomalies.deliberation/unregistered-artifact
                                 (messages/system-t :anomaly/unregistered-artifact)
                                 operation)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} provenance-stages
  "Ordered attestation stages. Run after `semantics/semantic-stages`, which
   establish the fields and types these read."
  [check-provenance check-evidence-links check-experiment-result check-artifact-export])
