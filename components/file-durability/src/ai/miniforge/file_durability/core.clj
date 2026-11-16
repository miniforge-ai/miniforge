;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.core
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.io :as file-io]
            [ai.miniforge.file-durability.boundary.directory-policy :as directory-policy]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare-protected-directory! [file]
  (boundary/call-with-exception-handling :prepare-directory file (partial directory/prepare! file) identity))

(defn ^{:stratum 0} resolve-protected-directory [file]
  (boundary/call-with-exception-handling :resolve-directory file (partial directory/existing! file) identity))

(defn ^{:stratum 0} filesystem-profile [file]
  (boundary/call-with-exception-handling :filesystem-profile file (partial directory-policy/filesystem-profile file) identity))

(defn ^{:stratum 0} protected-regular-file? [file]
  (boundary/call-with-exception-handling :file-protection file (partial directory-policy/protected-regular-file? file) identity))

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
