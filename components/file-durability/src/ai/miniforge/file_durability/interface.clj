;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.interface
  "API surface class 1: trusted-process filesystem primitives using caller-owned Files.
   Writes return the input File; resolution returns Paths and probes return acknowledgements or profiles.
   Failures return anomalies and must propagate; native defaults are not protection proof.
   Stores own path authorization, publication atomicity, and record identity.
   Complete writes force file contents; publication needs a subsequent confirm!."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.core :as core]
            [ai.miniforge.file-durability.spec :as spec]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare-protected-directory!
  "Return a resolved Path or anomaly. New POSIX directories are 0700; refuse
   unsafe owners or writable ancestry without chmod. Native providers retain
   their defaults; this does not establish their isolation or ACL protection."
  [directory]
  (if (m/validate spec/DirectoryFile directory)
    (core/prepare-protected-directory! directory)
    (boundary/invalid-input :prepare-directory)))

(defn ^{:stratum 0} resolve-protected-directory
  "Resolve an existing directory under the same protection policy, without creation."
  [directory]
  (if (m/validate spec/DirectoryFile directory)
    (core/resolve-protected-directory directory)
    (boundary/invalid-input :resolve-directory)))

(defn ^{:stratum 0} filesystem-profile
  "Return :posix, :native-defaults, or anomaly; native defaults are not protection proof."
  [file]
  (if (m/validate spec/DirectoryFile file)
    (core/filesystem-profile file)
    (boundary/invalid-input :filesystem-profile)))

(defn ^{:stratum 0} verify-publication!
  "Probe a protected, existing POSIX root for forced create-only and atomic replacement.
   On macOS, refuse ACLs granting additional permissions on the resolved path.
   Returns :publication-verified or an anomaly. Does not establish backend
   persistence or interoperability with another process."
  [root]
  (if (m/validate spec/DirectoryFile root)
    (core/verify-publication! root)
    (boundary/invalid-input :verify-publication)))

(defn ^{:stratum 0} write-new-text!
  "Create and force a new UTF-8 file without replacing existing files or links."
  [file text]
  (if (m/validate spec/TextWrite [file text])
    (core/write-new-text! file text)
    (boundary/invalid-input :write-new)))

(defn ^{:stratum 0} verify-runtime-locks!
  "Return :runtime-locks-verified or an anomaly for an existing canonical POSIX root.
   Trusted host configuration supplies an opposite installed runtime, absolute
   classpath and finite :lock/timeout-ms, :lock/poll-interval-ms, :lock/exit-timeout-ms.
   One host handshake deadline covers all marker/exit waits; cleanup may follow it.
   This disposable native proof does not qualify backend persistence or authorize effects."
  [root peer limits]
  (if (m/validate spec/RuntimeLockArguments [root peer limits])
    (core/verify-runtime-locks! root peer limits)
    (boundary/invalid-input :verify-runtime-locks)))

(defn ^{:stratum 0} verify-attached-directory
  "Return a canonical protected direct child on the parent's device, or refuse.
   The caller must already have established qualification of the parent store."
  [parent child]
  (if (m/validate spec/AttachedDirectoryArguments [parent child])
    (core/verify-attached-directory parent child)
    (boundary/invalid-input :verify-attached-directory)))

(defn ^{:stratum 0} write-temporary-bytes!
  "Fill and force an existing fresh temporary file without following a final symlink."
  [file bytes]
  (if (m/validate spec/ByteWrite [file bytes])
    (core/write-temporary-bytes! file bytes)
    (boundary/invalid-input :write-temporary)))

(defn ^{:stratum 0} publish-bytes!
  "Force temporary bytes, invoke publish! once, then force the held file and
   destination ancestry. publish! returns the destination java.io.File after
   applying permissions and atomic rename. The caller owns paths and cleanup.
   Holding the writable channel permits publication with read-only permissions."
  [file bytes publish!]
  (if (m/validate spec/PublicationWrite [file bytes publish!])
    (core/publish-bytes! file bytes publish!)
    (boundary/invalid-input :publish)))

(defn ^{:stratum 0} sync-ancestry!
  "Force directory entries from the supplied directory through its ancestors."
  [directory]
  (if (m/validate spec/File directory)
    (core/sync-ancestry! directory)
    (boundary/invalid-input :sync-ancestry)))

(defn ^{:stratum 0} confirm!
  "Force the published file and all ancestor directory entries before acknowledging."
  [file]
  (if (m/validate spec/File file)
    (core/confirm! file)
    (boundary/invalid-input :confirm)))

(comment
  (verify-runtime-locks! nil nil nil)
  (verify-attached-directory nil nil))
