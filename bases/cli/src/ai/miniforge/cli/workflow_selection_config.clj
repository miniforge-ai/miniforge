(ns ai.miniforge.cli.workflow-selection-config
  "Resource-driven workflow selection profile resolution."
  (:require
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def selection-profiles-resource
  "Classpath resource path for workflow selection profile mappings."
  "config/workflow/selection-profiles.edn")

(defn- read-selection-profile-config
  "Read a single selection profile config resource."
  [resource]
  (let [config (-> resource slurp edn/read-string)]
    (or (:workflow-selection/profiles config) {})))

(defn configured-selection-profiles
  "Merge workflow selection profile mappings from all matching classpath resources."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       selection-profiles-resource))
       (map read-selection-profile-config)
       (apply merge {})))

;------------------------------------------------------------------------------ Layer 1
;; Generic fallback resolution

(defn- workflow-characteristics
  "Resolve workflow characteristics through the workflow interface."
  [workflow]
  (when-let [characteristics-fn (requiring-resolve
                                  'ai.miniforge.workflow.interface/workflow-characteristics)]
    (characteristics-fn workflow)))

(defn- simplest-workflow-id
  "Choose the simplest available workflow by phase count and max iterations."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [phases max-iterations])))
       first
       :workflow/id))

(defn- most-comprehensive-workflow-id
  "Choose the most comprehensive available workflow by phase count."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [(- phases) (- max-iterations)])))
       first
       :workflow/id))

(defn- resolve-profile-fallback
  "Resolve a profile via generic workflow characteristics when no config is present."
  [profile available-workflows]
  (case profile
    :comprehensive (most-comprehensive-workflow-id available-workflows)
    :fast (simplest-workflow-id available-workflows)
    :default (or (resolve-profile-fallback :fast available-workflows)
                 (most-comprehensive-workflow-id available-workflows))
    nil))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn resolve-selection-profile
  "Resolve a logical selection profile to a concrete workflow id.

   Profiles are app-owned configuration. If a configured profile points at a
   workflow not present on the active classpath, fall back to generic workflow
   characteristics."
  ([profile]
   (let [available-workflows ((requiring-resolve 'ai.miniforge.workflow.interface/list-workflows))]
     (resolve-selection-profile profile available-workflows)))
  ([profile available-workflows]
   (let [configured-id (get (configured-selection-profiles) profile)
         available-ids (set (map :workflow/id available-workflows))]
     (cond
       (contains? available-ids configured-id) configured-id
       :else (resolve-profile-fallback profile available-workflows)))))
