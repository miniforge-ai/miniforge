(ns ai.miniforge.workflow.interface.profiles
  "Workflow-owned state-profile provider helpers."
  (:require
   [ai.miniforge.workflow.state-profile :as state-profile]))

(defn load-state-profile-provider
  []
  (state-profile/load-state-profile-provider))

(defn available-state-profile-ids
  []
  (state-profile/available-state-profile-ids))

(defn default-state-profile-id
  []
  (state-profile/default-state-profile-id))

(defn resolve-state-profile
  [profile]
  (state-profile/resolve-state-profile profile))
