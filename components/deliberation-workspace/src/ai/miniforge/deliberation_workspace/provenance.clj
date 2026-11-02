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
   [ai.miniforge.deliberation-workspace.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} attested-source-classes
  "Evidence classes that satisfy acceptance (N14 §2.4) and so must match a
   runtime receipt rather than a model assertion."
  #{:execution :user})

(def ^{:stratum 0} artifact-ref-attributes [:capsule-id :digest :paths])

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
         (receipt-matches? receipt :execution (:object/statement evidence)))))

(defn- ^{:stratum 1} check-provenance [workspace operation {:keys [role]}]
  (when (some #(unattested-spec? workspace role %) (validation/created-specs operation))
    (validation/reject-operation :anomalies.deliberation/unattested-provenance
                                 (messages/system-t :anomaly/unattested-provenance)
                                 operation)))

(defn- ^{:stratum 1} check-evidence-links
  "Attaching existing evidence names it under `:supports`, and every
   `:supports` destination of an attachment is evidence."
  [workspace operation _context]
  (when (= :attach-evidence (:op operation))
    (let [supports (get-in operation [:links :supports] [])
          created (into {} (map (juxt :id :type)) (validation/created-specs operation))
          type-of #(or (:object/type (get (validation/objects-of workspace) %)) (get created %))]
      (when (or (and (empty? (validation/created-specs operation)) (empty? supports))
                (some #(not= :evidence (type-of %)) supports))
        (validation/reject-operation :anomalies.deliberation/evidence-link
                                     (messages/system-t :anomaly/evidence-link) operation)))))

(defn- ^{:stratum 1} check-experiment-result [workspace operation _context]
  (when (and (= :record-experiment-result (:op operation))
             (not (some #(attested-execution? workspace %) (get operation :evidence []))))
    (validation/reject-operation :anomalies.deliberation/unbacked-result
                                 (messages/system-t :anomaly/unbacked-result) operation)))

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
