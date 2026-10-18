;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.semantic-rules
  "Pure N6 resource-count policy shared by production and canonical validation.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rules
  {:import  {:creates 0 :updates 0 :destroys 0}
   :create  {:creates :pos :updates :any :destroys 0}
   :update  {:creates 0 :updates :pos :destroys 0}
   :destroy {:creates 0 :updates 0 :destroys :pos}
   :refactor {:creates 0 :updates 0 :destroys 0}
   :migrate {:creates :pos :updates 0 :destroys :pos}})

(def ^{:stratum 0} empty-changes {:creates 0 :updates 0 :destroys 0})

(def ^{:stratum 0} count-kinds [:creates :updates :destroys])

(def ^{:stratum 0} rule-ids
  {:creates "semantic-creates" :updates "semantic-updates"
   :destroys "semantic-destroys" :balance "semantic-migration-balance"})

(defn ^{:stratum 0} check-count-rule [expected actual]
  (case expected
    0 (= 0 actual)
    :pos (pos? actual)
    :any true
    (= expected actual)))

(defn ^{:stratum 0} inferred-behavior [{:keys [creates updates destroys]}]
  (cond
    (and (pos? creates) (pos? destroys)) :migrate
    (pos? creates) :create
    (pos? updates) :update
    (pos? destroys) :destroy
    :else :import))

(defn- ^{:stratum 0} unbalanced-migration? [intent counts]
  (and (= :migrate intent) (not= (:creates counts) (:destroys counts))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} count-passes? [intent counts kind]
  (check-count-rule (get-in rules [intent kind]) (get counts kind)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} failed-rules [intent counts]
  (cond-> (into [] (remove (partial count-passes? intent counts)) count-kinds)
    (unbalanced-migration? intent counts) (conj :balance)))

(comment
  (failed-rules :migrate {:creates 1 :updates 0 :destroys 2}))
