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
(ns ai.miniforge.deliberation-workspace.object
  "The closed workspace object taxonomy of N14 §2 and its structural status
   model (§2.3). Pure data: no clock, no IO, no numeric confidence."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} object-types
  "Closed set of workspace object types (N14 §2.1). Anything else is rejected."
  #{:goal :constraint :question :claim :hypothesis :experiment :evidence
    :plan :decision :artifact-ref :conflict :blocker})

(def ^{:stratum 0} link-types
  "Typed edges between objects (N14 §2.1)."
  #{:supports :contradicts :depends-on :resolves :discriminates :supersedes})

(def ^{:stratum 0} evidence-source-classes
  "Provenance classes for evidence (N14 §2.4). Only :execution and :user
   satisfy the acceptance rule in [[acceptable-claim?]]."
  #{:execution :retrieval :user :agent-analysis})

(def ^{:stratum 0} ^:private deliberative-statuses
  #{:open :contested :accepted :rejected :superseded})

(def ^{:stratum 0} terminal-statuses
  "Statuses from which no further transition is legal."
  #{:accepted :rejected :superseded :answered :retired :completed :aborted
    :resolved})

(defn ^{:stratum 0} initial-status
  "The status a newly created object of `object-type` carries."
  [object-type]
  (case object-type
    :experiment :proposed
    :open))

(defn ^{:stratum 0} touch
  "Mark `object` as touched at workspace `version`. Staleness (N14 §6.1) is
   measured in committed transactions since this mark, never wall time."
  [object version]
  (assoc object :object/touched-at version))

(defn ^{:stratum 0} linked
  "The set of object ids `object` points at along `link-type`."
  [object link-type]
  (get-in object [:object/links link-type] #{}))

(defn ^{:stratum 0} hard-constraint?
  "True when `object` is a constraint the workspace must never let an agent
   modify or retire (N14 §2.4)."
  [object]
  (and (= :constraint (:object/type object))
       (= :hard (get-in object [:object/attrs :kind]))))

(defn ^{:stratum 0} execution-grade?
  "True when `evidence` carries a source class that can satisfy claim
   acceptance (N14 §2.4): produced by execution, or asserted by the user."
  [evidence]
  (contains? #{:execution :user} (get-in evidence [:object/attrs :source-class])))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} status-model
  "Legal statuses per object type (N14 §2.3). Closed per type."
  {:goal #{:open :accepted :rejected}
   :claim deliberative-statuses
   :hypothesis deliberative-statuses
   :plan deliberative-statuses
   :decision deliberative-statuses
   :question #{:open :answered :retired}
   :experiment #{:proposed :running :completed :aborted}
   :constraint #{:open}
   :evidence #{:open}
   :artifact-ref #{:open :superseded}
   :conflict #{:open :resolved}
   :blocker #{:open :resolved}})

(defn ^{:stratum 1} terminal?
  "True when `object` sits in a status admitting no further transition."
  [object]
  (contains? terminal-statuses (:object/status object)))

(defn- ^{:stratum 1} known-type? [object-type]
  (contains? object-types object-type))

(defn ^{:stratum 1} add-link
  "Add a typed edge from `object` to `target-id`."
  [object link-type target-id]
  (when-not (contains? link-types link-type)
    (throw (IllegalArgumentException. (str "Unknown link type: " link-type))))
  (update-in object [:object/links link-type] (fnil conj #{}) target-id))

(defn ^{:stratum 1} acceptable-claim?
  "Structural acceptance rule of N14 §2.3: a claim may be accepted only when
   it carries at least one execution- or user-grade evidence link (the
   provenance classes of §2.4) and no unresolved challenge. The alternative
   route — acceptance by explicit decision — is applied by the transaction
   layer, not here.

   `evidence-objects` are the objects the claim links via :supports;
   `open-challenges` counts unresolved challenges referencing it."
  [evidence-objects open-challenges]
  (and (zero? open-challenges)
       (boolean (some execution-grade? evidence-objects))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} legal-status?
  "True when `status` is legal for `object-type`."
  [object-type status]
  (contains? (get status-model object-type #{}) status))

(defn ^{:stratum 2} new-object
  "Construct a workspace object. `attrs` carries type-specific fields
   (:kind for claims and constraints, :source-class for evidence).
   `version` is the workspace version at creation, which also seeds
   :object/touched-at — the staleness clock of N14 §6.1.

   Throws IllegalArgumentException on an unknown type or blank statement:
   both are programmer errors, not agent-reachable anomalies."
  [{:keys [id type statement role activation version links attrs]}]
  (when-not (known-type? type)
    (throw (IllegalArgumentException. (str "Unknown workspace object type: " type))))
  (when (str/blank? statement)
    (throw (IllegalArgumentException. (str "Object " id " requires a statement"))))
  (when-let [unknown (seq (remove link-types (keys links)))]
    (throw (IllegalArgumentException. (str "Unknown link types: " (vec unknown)))))
  (when-let [bad (seq (remove (comp coll? val) links))]
    (throw (IllegalArgumentException.
            (str "Link values must be collections of object ids: " (vec (map key bad))))))
  {:object/id id
   :object/type type
   :object/status (initial-status type)
   :object/statement statement
   :object/role role
   :object/activation activation
   :object/version version
   :object/touched-at version
   :object/links (merge (zipmap link-types (repeat #{}))
                        (into {} (map (fn [[edge targets]] [edge (set targets)])) links))
   :object/attrs (or attrs {})})
