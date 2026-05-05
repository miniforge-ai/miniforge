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

(ns ai.miniforge.progress-detector.tool-profile
  "EDN-driven tool-profile registry.

   Loads tool profiles from resources/config/progress_detector/tool-profiles.edn.
   Each profile describes a known tool's determinism level and anomaly
   detection characteristics.

   Public API:
     load-registry   - parse EDN resource into validated registry map
     lookup          - retrieve a profile by tool-id keyword
     determinism-of  - return the :determinism level for a tool
     categories-of   - return the anomaly category set for a tool
     all-tool-ids    - return all registered tool keyword ids
     register        - add/override a profile at runtime (returns new registry)
     validate-all    - validate all entries against ToolProfile schema"
  (:require
   [clojure.edn  :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.progress-detector.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Resource loading

(def ^:private default-resource-path
  "config/progress_detector/tool-profiles.edn")

(defn- load-edn-resource
  "Load and parse an EDN resource from the classpath.
   Returns parsed EDN value or throws ex-info on failure."
  [resource-path]
  (let [url (io/resource resource-path)]
    (when-not url
      (throw (ex-info "Tool-profile resource not found"
                      {:resource resource-path})))
    (with-open [rdr (io/reader url)]
      (edn/read (java.io.PushbackReader. rdr)))))

;; ---------------------------------------------------------------------------
;; Registry construction and validation

(defn validate-all
  "Validate every entry in registry against ToolProfile schema.

   Arguments:
     registry - map of tool-id -> profile

   Returns: Map of tool-id -> {:valid? bool :errors humanized-errors-or-nil}"
  [registry]
  (reduce-kv
   (fn [acc tool-id profile]
     (assoc acc tool-id
            {:valid?  (schema/valid-tool-profile? profile)
             :errors  (when-not (schema/valid-tool-profile? profile)
                        (schema/explain-anomaly profile))}))
   {}
   registry))

(defn load-registry
  "Load and return the tool-profile registry from classpath EDN.

   Optionally accepts a resource-path override (useful in tests).
   Performs schema validation and emits warnings for invalid profiles;
   does NOT throw on invalid profiles to avoid crashing at startup.

   Returns: map of tool-id-keyword -> profile-map"
  ([]
   (load-registry default-resource-path))
  ([resource-path]
   (let [raw (load-edn-resource resource-path)]
     (when-not (map? raw)
       (throw (ex-info "Tool-profile registry must be a map"
                       {:resource resource-path :actual-type (type raw)})))
     raw)))

;; ---------------------------------------------------------------------------
;; Default registry (loaded once at startup; held in a var)

(defonce ^:private default-registry
  (delay (load-registry)))

(defn- get-default-registry []
  @default-registry)

;; ---------------------------------------------------------------------------
;; Query API

(defn lookup
  "Return the profile map for tool-id from registry, or nil if unknown.

   Arguments:
     tool-id  - qualified keyword, e.g. :tool/bash
     registry - (optional) registry map; defaults to built-in EDN registry"
  ([tool-id]
   (lookup tool-id (get-default-registry)))
  ([tool-id registry]
   (get registry tool-id)))

(defn determinism-of
  "Return the :determinism level for tool-id, or :volatile if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (determinism-of tool-id (get-default-registry)))
  ([tool-id registry]
   (get (lookup tool-id registry) :determinism :volatile)))

(defn categories-of
  "Return the set of anomaly categories for tool-id, or #{} if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (categories-of tool-id (get-default-registry)))
  ([tool-id registry]
   (get (lookup tool-id registry) :anomaly/categories #{})))

(defn detector-kind-of
  "Return the :detector/kind for tool-id, or :detector.kind/shell if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (detector-kind-of tool-id (get-default-registry)))
  ([tool-id registry]
   (get (lookup tool-id registry) :detector/kind :detector.kind/shell)))

(defn all-tool-ids
  "Return sorted vector of all registered tool-id keywords.

   Arguments:
     registry - (optional) registry map"
  ([]
   (all-tool-ids (get-default-registry)))
  ([registry]
   (vec (sort (keys registry)))))

;; ---------------------------------------------------------------------------
;; Registry manipulation (returns new registry, never mutates)

(defn register
  "Return a new registry with profile added/overwritten for tool-id.

   Arguments:
     registry - existing registry map
     tool-id  - qualified keyword to register
     profile  - profile map (should satisfy ToolProfile schema)

   Returns: New registry map."
  [registry tool-id profile]
  (assoc registry tool-id profile))

(defn unregister
  "Return a new registry with tool-id removed.

   Arguments:
     registry - existing registry map
     tool-id  - qualified keyword to remove

   Returns: New registry map."
  [registry tool-id]
  (dissoc registry tool-id))

;; ---------------------------------------------------------------------------
;; Rich comment

(comment
  (def reg (load-registry))

  (keys reg)
  ;; => (:tool/bash :tool/write :tool/edit ...)

  (lookup :tool/bash reg)
  ;; => {:detector/kind :detector.kind/shell
  ;;     :determinism :volatile
  ;;     :anomaly/categories #{:anomaly.category/stall ...}
  ;;     :timeout-ms 120000}

  (determinism-of :tool/agent reg)
  ;; => :nondeterministic

  (determinism-of :tool/unknown reg)
  ;; => :volatile   (safe default)

  (categories-of :tool/read reg)
  ;; => #{:anomaly.category/loop}

  (all-tool-ids reg)
  ;; => [:tool/agent :tool/bash :tool/edit ...]

  (-> reg
      (register :tool/custom {:detector/kind :detector.kind/shell
                              :determinism   :stable
                              :anomaly/categories #{:anomaly.category/error}})
      (lookup :tool/custom))
  ;; => {:detector/kind :detector.kind/shell ...}

  :leave-this-here)
