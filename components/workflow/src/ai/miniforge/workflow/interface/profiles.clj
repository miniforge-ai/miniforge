(ns ai.miniforge.workflow.interface.profiles
  "Workflow-owned state-profile provider helpers.")

(defn load-state-profile-provider
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/load-state-profile-provider)))

(defn available-state-profile-ids
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/available-state-profile-ids)))

(defn default-state-profile-id
  []
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/default-state-profile-id)))

(defn resolve-state-profile
  [profile]
  ((requiring-resolve 'ai.miniforge.workflow.state-profile/resolve-state-profile) profile))
