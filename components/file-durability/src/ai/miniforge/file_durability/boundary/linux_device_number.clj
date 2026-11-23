;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-device-number
  "Match stat's encoded device to mountinfo's kernel major:minor (linux/kdev_t.h).")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} major-shift 8)

(def ^{:stratum 0 :private true} major-mask 0xfff)

(def ^{:stratum 0 :private true} low-minor-mask 0xff)

(def ^{:stratum 0 :private true} high-minor-shift 12)

(def ^{:stratum 0 :private true} high-minor-mask 0xfff00)

(def ^{:stratum 0 :private true} encoded-limit
  "Linux new_encode_dev and huge_encode_dev emit the same unsigned 32-bit layout."
  0xffffffff)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} major-number [encoded]
  (bit-and (unsigned-bit-shift-right encoded major-shift) major-mask))

(defn- ^{:stratum 1} minor-number [encoded]
  (bit-or (bit-and encoded low-minor-mask)
          (bit-and (unsigned-bit-shift-right encoded high-minor-shift) high-minor-mask)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} matches? [kernel-device encoded]
  (and (<= 0 encoded encoded-limit)
       (= kernel-device (str (major-number encoded) ":" (minor-number encoded)))))

(comment (matches? "8:0" 2048))
