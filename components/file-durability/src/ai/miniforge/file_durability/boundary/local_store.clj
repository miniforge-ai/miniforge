;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.local-store
  "Bind an explicitly selected storage profile to an existing protected root."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-policy :as permissions]
            [ai.miniforge.file-durability.boundary.linux-store :as linux]
            [ai.miniforge.file-durability.boundary.mac-store :as mac]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} observers
  "A supported store can be observed only in its owning operating system."
  {[:linux-block-posix "Linux"] linux/observe
   [:macos-apfs-internal "Mac OS X"] mac/observe})

(defn- ^{:stratum 0} observe-root [observe root]
  (let [before (identity/read-identity root)
        store (when before (observe root))
        after (identity/read-identity root)]
    (when (and store (= before after))
      (merge store before {:storage/root root}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe [profile root]
  (if-let [native-probe (get observers [profile (System/getProperty "os.name")])]
    (let [path (directory/existing! root)
          evidence (when (= :posix (permissions/filesystem-profile path))
                     (observe-root native-probe path))]
      (or evidence (boundary/unqualified-store root profile)))
    (boundary/unqualified-store root profile)))
