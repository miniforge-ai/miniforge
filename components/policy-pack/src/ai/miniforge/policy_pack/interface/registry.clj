(ns ai.miniforge.policy-pack.interface.registry
  "Registry-facing policy-pack API."
  (:require
   [ai.miniforge.policy-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Registry protocol and helpers

(def PolicyPackRegistry registry/PolicyPackRegistry)
(def create-registry registry/create-registry)
(def register-pack registry/register-pack)
(def get-pack registry/get-pack)
(def get-pack-version registry/get-pack-version)
(def list-packs registry/list-packs)
(def delete-pack registry/delete-pack)
(def resolve-pack registry/resolve-pack)
(def get-rules-for-context registry/get-rules-for-context)
(def glob-matches? registry/glob-matches?)
(def compare-versions registry/compare-versions)
