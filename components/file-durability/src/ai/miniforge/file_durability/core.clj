;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.core
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.attached-directory :as attached]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.io :as file-io]
            [ai.miniforge.file-durability.boundary.directory-policy :as directory-policy]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication-probe]
            [ai.miniforge.file-durability.boundary.qualified-root :as qualified-root]
            [ai.miniforge.file-durability.boundary.root-qualification :as qualification]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare-protected-directory! [file]
  (boundary/call-with-exception-handling :prepare-directory file (partial directory/prepare! file) identity))

(defn ^{:stratum 0} resolve-protected-directory [file]
  (boundary/call-with-exception-handling :resolve-directory file (partial directory/existing! file) identity))

(defn ^{:stratum 0} filesystem-profile [file]
  (boundary/call-with-exception-handling :filesystem-profile file (partial directory-policy/filesystem-profile file) identity))

(defn ^{:stratum 0} verify-publication! [root]
  (boundary/call-with-exception-handling :verify-publication root
                                        #(publication-probe/check! (directory/existing! root)) identity))

(defn ^{:stratum 0} qualify-local-root! [root profile peer limits]
  (boundary/call-with-exception-handling :qualify-local-root root
                                        (partial qualification/qualify! root profile peer limits) identity))

(defn ^{:stratum 0} release-qualified-root! [root]
  (if (qualified-root/qualified? root)
    (boundary/call-with-exception-handling :release-qualified-root nil
                                          (partial lease/release! root) (constantly :qualified-root-released))
    (boundary/invalid-input :release-qualified-root)))

(defn ^{:stratum 0} resolve-qualified-root [root]
  (if (qualified-root/qualified? root)
    (boundary/call-with-exception-handling :resolve-qualified-root nil
                                          (partial qualified-root/resolve-root root) identity)
    (boundary/invalid-input :resolve-qualified-root)))

(defn ^{:stratum 0} verify-attached-directory [parent child]
  (boundary/call-with-exception-handling :verify-attached-directory child
                                        (partial attached/check! parent child) identity))

(defn ^{:stratum 0} write-new-text! [file encoded]
  (boundary/call-with-exception-handling :write-new file
                                        (partial file-io/write-new-text! file encoded)))

(defn ^{:stratum 0} write-temporary-bytes! [file bytes]
  (boundary/call-with-exception-handling :write-temporary file
                                        (partial file-io/write-temporary-bytes! file bytes)))

(defn ^{:stratum 0} sync-ancestry! [directory]
  (boundary/call-with-exception-handling :sync-ancestry directory
                                        (partial file-io/sync-ancestry! directory)))

(defn ^{:stratum 0} confirm! [file]
  (boundary/call-with-exception-handling :confirm file (partial file-io/confirm! file)))

(defn ^{:stratum 0} publish-bytes! [file bytes publish!]
  (boundary/call-with-exception-handling
   :publish file (partial file-io/publish-bytes! file bytes publish!) identity))

(comment
  ::durability-boundary)
