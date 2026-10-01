;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.publication
  "Required published-evidence linkage and reliability values (N6 2.6/2.12)."
  (:require [ai.miniforge.reliability.interface :as reliability]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} EventLink
  [:map
   [:event-links/scope-type [:enum :workflow :pr :pack :repo :supervisory-entity :deployment]]
   [:event-links/scope-id some?]
   [:event-links/from-sequence nat-int?]
   [:event-links/to-sequence nat-int?]
   [:event-links/event-count pos-int?]])

(defn- ^{:stratum 0} scope [link]
  ((juxt :event-links/scope-type :event-links/scope-id) link))

(def ^{:stratum 0} scope-id-schemas
  {:workflow :uuid :pr :uuid :pack :string :repo :string :deployment :string
   :supervisory-entity [:or :uuid :string [:tuple :string pos-int?]]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid-link? [workflow-id link]
  (and (m/validate EventLink link)
       (m/validate (get scope-id-schemas (:event-links/scope-type link)) (:event-links/scope-id link))
       (<= (:event-links/from-sequence link) (:event-links/to-sequence link))
       (<= (:event-links/event-count link)
           (inc (- (:event-links/to-sequence link) (:event-links/from-sequence link))))
       (or (not= :workflow (:event-links/scope-type link))
           (= workflow-id (:event-links/scope-id link)))))

(defn- ^{:stratum 1} distinct-scopes? [links]
  (= (count links) (count (set (map scope links)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} errors [bundle]
  (let [links (:evidence/event-links bundle)
        sealed? (some #(contains? bundle %) [:evidence/content-hash :evidence/signature :evidence/sealed-at])]
    (cond-> []
      (and (or sealed? (contains? (:evidence/outcome bundle) :outcome/tier))
           (not (m/validate reliability/WorkflowTier (get-in bundle [:evidence/outcome :outcome/tier]))))
      (conj {:code :invalid-outcome-tier})
      (and (or sealed? (contains? bundle :evidence/event-links))
           (not (and (vector? links) (seq links) (distinct-scopes? links)
                     (every? (partial valid-link? (:evidence-bundle/workflow-id bundle)) links))))
      (conj {:code :invalid-event-links}))))

(comment
  (errors {}))
