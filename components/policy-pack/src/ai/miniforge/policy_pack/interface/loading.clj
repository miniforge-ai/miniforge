(ns ai.miniforge.policy-pack.interface.loading
  "Pack loading and serialization helpers."
  (:require
   [ai.miniforge.policy-pack.loader :as loader]))

;------------------------------------------------------------------------------ Layer 0
;; Loading operations

(def load-pack loader/load-pack)
(def load-pack-from-file loader/load-pack-from-file)
(def load-pack-from-directory loader/load-pack-from-directory)
(def discover-packs loader/discover-packs)
(def load-all-packs loader/load-all-packs)
(def write-pack-to-file loader/write-pack-to-file)
