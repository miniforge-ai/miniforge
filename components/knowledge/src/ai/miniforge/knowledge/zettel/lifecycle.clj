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
(ns ai.miniforge.knowledge.zettel.lifecycle
  "Zettel creation and updates over the revision-stamping boundary."
  (:require
   [ai.miniforge.knowledge.zettel.revision :as revision]
   [ai.miniforge.knowledge.zettel.stamp :as stamp]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-zettel
  "Create a zettel and stamp its initial untrusted revision."
  [uid title content type
   & {:keys [dewey tags links source author
             fleet/shareable fleet/share-scope privacy/classification
             fleet/oss-version]
      :or {author "user"}}]
  (let [now (java.util.Date.)
        zettel (cond-> {:zettel/id (random-uuid)
                         :zettel/uid uid
                         :zettel/title title
                         :zettel/content content
                         :zettel/type type
                         :zettel/created now
                         :zettel/author author
                         :zettel/trust-level :untrusted}
                 dewey (assoc :zettel/dewey dewey)
                 (seq tags) (assoc :zettel/tags (vec tags))
                 (seq links) (assoc :zettel/links (vec links))
                 source (assoc :zettel/source source)
                 (some? shareable) (assoc :fleet/shareable shareable)
                 share-scope (assoc :fleet/share-scope share-scope)
                 classification (assoc :privacy/classification classification)
                 oss-version (assoc :fleet/oss-version oss-version))]
    (stamp/stamp-revision zettel)))

(defn ^{:stratum 0} update-zettel
  "Update a zettel while preserving or resetting trust by revision."
  [zettel changes]
  (let [old-rev (:zettel/revision-id zettel)
        old-trust (:zettel/trust-level zettel)
        sanitised (apply dissoc changes revision/derived-fields)
        merged (-> zettel
                   (merge sanitised)
                   (assoc :zettel/modified (java.util.Date.)))
        stamped (stamp/stamp-revision merged)
        new-rev (:zettel/revision-id stamped)
        next-trust (cond
                     (and (some? old-rev) (not= old-rev new-rev)) :untrusted
                     (some? old-trust) old-trust
                     :else :untrusted)]
    (assoc stamped :zettel/trust-level next-trust)))
