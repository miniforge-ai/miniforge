;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.main
  "Standalone development lab. Explicit run starts provider calls; smoke is offline."
  (:require [ai.miniforge.deliberation-lab.adapter :as adapter]
            [ai.miniforge.deliberation-lab.codec :as codec]
            [ai.miniforge.deliberation-lab.experiment :as experiment]
            [ai.miniforge.deliberation-lab.fixtures :as fixtures]
            [ai.miniforge.deliberation-lab.journal :as journal]
            [ai.miniforge.deliberation-workspace.interface :as board]
            [clojure.java.io :as io]))

(defn validate-manifest! [m]
  (when-not (and (= :development (:mode m)) (= 1 (:schema-version m))
                 (string? (:model m)) (not (empty? (:model m)))
                 (pos-int? (:replicates m)) (pos-int? (:max-input-bytes m))
                 (seq (:conditions m)) (every? experiment/conditions (:conditions m))
                 (= (count (:conditions m)) (count (set (:conditions m)))))
    (throw (ex-info "Invalid development manifest" {})))
  (when-not (and (seq (:tiers m))
                 (= (count (:tiers m)) (count (set (map :id (:tiers m))))))
    (throw (ex-info "Tiers must be nonempty and uniquely named" {})))
  (doseq [tier (:tiers m)]
    (when-not (and (keyword? (:id tier)) (int? (:activations tier)) (>= (:activations tier) 2)
                   (number? (:max-normalized-usd tier)) (pos? (:max-normalized-usd tier))
                   (number? (:wall-seconds tier)) (pos? (:wall-seconds tier)))
      (throw (ex-info "Each tier needs a total allowance including closure and positive ceilings" {}))))
  (when-not (every? #(and (number? %) (not (neg? %)))
                   ((juxt :input-per-million :output-per-million) (:price-table m)))
    (throw (ex-info "Pinned normalized token prices required" {})))
  (when-not (and (pos-int? (get-in m [:adapter :timeout-seconds]))
                 (number? (get-in m [:adapter :call-cap-usd]))
                 (pos? (get-in m [:adapter :call-cap-usd])))
    (throw (ex-info "Adapter requires timeout and provider dollar cap" {})))
  m)

(defn mock-call []
  (let [counter (atom 0)]
    (fn [request]
      (let [n (swap! counter inc)
            task (first (filter #(= (:id %) (get-in request [:task :id])) fixtures/tasks))]
        {:payload {:operations [{:op :assert-claim :creates [{:id (str "mock-" n) :type :claim
                                                             :statement "SYNTHETIC offline plumbing contribution"}]}]
                   :inspect (:available-experiments request)
                   :solution (:reference task) :rationale "SYNTHETIC mock reference; not model evidence"}
         :usage {:input 100 :output 20 :provider-usd 0}}))))

(def implementation-resources
  (concat (map #(str "ai/miniforge/deliberation_lab/" % ".clj")
               ["main" "adapter" "codec" "experiment" "fixtures" "journal"])
          (map #(str "ai/miniforge/deliberation_workspace/" % ".clj")
               ["admission" "semantics" "commit" "guards" "validation" "projection"
                "run" "scheduler" "termination" "object" "transaction" "conflict"])))
(defn implementation-hash []
  (codec/digest (into {} (map (fn [path] [path (slurp (io/resource path))])) implementation-resources)))

(defn execute! [manifest output call]
  (validate-manifest! manifest)
  (fixtures/validate-fixtures!)
  (let [manifest (assoc manifest :prompt-hash (codec/digest adapter/system-prompt)
                                :task-hash (codec/digest fixtures/tasks)
                                :implementation-hash (implementation-hash)
                                :protocol-version "deliberation-lab/1")
        log (journal/open! output manifest)
        write! #(journal/append! log %)]
    (write! {:kind :experiment-opened :manifest manifest :tasks fixtures/tasks})
    (let [results
          (vec (for [task fixtures/tasks condition (:conditions manifest)
                     tier (:tiers manifest) replicate (range (:replicates manifest))]
                 (do (println "Running" (:id task) condition (:id tier) replicate)
                     (experiment/run-cell manifest task condition tier replicate call write!))))
          report (experiment/report manifest results)]
      (write! {:kind :experiment-completed :manifest-hash (codec/digest manifest)
               :results (mapv #(dissoc % :snapshots :audit) results)
               :report report :gate {:G0 :not-evaluated}})
      (spit (io/file output "report.md") report)
      {:cells (count results) :journal output :G0 :not-evaluated})))

(defn verify! [directory]
  (let [records (journal/read! directory)
        header (:payload (first records))
        tasks (into {} (map (juxt :id identity)) (:tasks header))
        turns (filter #(= :activation (:kind %)) (map :payload records))]
    (when-not (and (= :experiment-opened (:kind header))
                   (= :experiment-completed (get-in (last records) [:payload :kind])))
      (throw (ex-info "Incomplete experiment journal" {})))
    (doseq [{:keys [task workspace]} turns]
      (let [replayed (reduce board/replay-transaction (experiment/initial-workspace (tasks task))
                             (:workspace/log workspace))]
        (when-not (= (:workspace/objects replayed) (:workspace/objects workspace))
          (throw (ex-info "Graph does not replay from its transaction history" {:task task})))))
    {:records (count records) :replayed-activations (count turns) :verified? true}))

(defn -main [& [command manifest-path output]]
  (case command
    "verify" (prn (verify! manifest-path))
    ("run" "smoke")
    (let [manifest (codec/read-file manifest-path)
          mock? (= command "smoke")
          manifest (assoc manifest :adapter-kind (if mock? :mock :claude-cli))]
      (validate-manifest! manifest)
      (prn (execute! manifest output
                     (if mock? (mock-call)
                         (partial adapter/claude-call (merge (:adapter manifest)
                                                            {:model (:model manifest)}))))))
    (throw (ex-info "Usage: clojure -M:run smoke|run manifest.edn NEW_OUTPUT_DIR; or verify DIR" {}))))
