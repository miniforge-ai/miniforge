(ns ai.miniforge.policy-calibration.runner
  "Live calibration run: load config + rules + corpus, build the LLM judge,
   calibrate, and write the record. The thin `policy:calibrate` bb task calls
   `run-calibration!`; all logic lives here, not in bb.edn."
  (:require [ai.miniforge.policy-calibration.config :as config]
            [ai.miniforge.policy-calibration.core :as core]
            [ai.miniforge.policy-calibration.judge :as judge]
            [ai.miniforge.policy-pack.interface :as pp]
            [ai.miniforge.llm.interface :as llm]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(def ^:private truth-filename
  "Corpus ground-truth file: maps each fixture to the rules it violates."
  "truth.edn")

(def ^:private fixture-suffix
  "On-disk suffix for fixture files — stored as <key>.txt so clj-kondo's *.clj
   staged-lint skips these intentional rule-violations."
  ".txt")

(defn- read-pack [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn- load-rules
  "The corpus's :candidate-rules, resolved from the shipped packs."
  [pack-paths candidate-ids]
  (let [by-id   (into {} (map (juxt :rule/id identity))
                      (pp/resolve-rules (mapcat #(:pack/rules (read-pack %) []) pack-paths)))
        missing (remove by-id candidate-ids)]
    (when (seq missing)
      (throw (IllegalArgumentException.
              (str "candidate rules missing from packs: " (vec missing)))))
    (mapv by-id candidate-ids)))

(defn- load-corpus
  "Read truth.edn + the seeded fixture files (stored as <key>.txt) from corpus-root."
  [corpus-root]
  (let [truth (edn/read-string (slurp (io/file corpus-root truth-filename)))]
    {:candidate-rules (:candidate-rules truth)
     :fixtures (vec (for [[rel m] (:fixtures truth)]
                      {:rel     rel
                       :content (slurp (io/file corpus-root (str rel fixture-suffix)))
                       :seeded  (:violates m)}))}))

(defn- write-record! [record cfg]
  (io/make-parents (io/file (:record-out cfg)))
  (spit (:record-out cfg)
        (with-out-str
          (pprint/pprint {:generated-by "policy-calibration/runner"
                          :model        (get-in cfg [:model :model])
                          :trials       (:trials cfg)
                          :runs         (:runs cfg)
                          :gate-bar     (:gate-bar cfg)
                          :rules        record}))))

(defn run-calibration!
  "Run the calibration end to end and write the record; returns the record."
  []
  (let [cfg     (config/load-config)
        {:keys [candidate-rules fixtures]} (load-corpus (:corpus-root cfg))
        rules   (load-rules (:pack-paths cfg) candidate-rules)
        client  (llm/create-client (select-keys (:model cfg) [:backend :model]))
        judge-fn (judge/make-judge client llm/complete)
        record  (core/calibrate {:rules rules :fixtures fixtures :judge-fn judge-fn
                                 :runs (:runs cfg) :trials (:trials cfg)
                                 :max-parallel (:max-parallel cfg)
                                 :gate-bar (:gate-bar cfg)})]
    (write-record! record cfg)
    record))

(comment
  ;; The `policy:calibrate` bb task is `(run-calibration!)`. Reads config.edn,
  ;; the shipped packs, and the corpus; writes the record to :record-out.
  (run-calibration!)
  :leave-this-here)
