;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.workbench-sweep.core
  "The permutation sweep runner: execute the same logical task under
   every variant of a declared matrix (workflow chain x prompt x model x
   method x open axes), k replicates each, and emit one variant-tagged
   `workbench_snapshot/v1` per run — a directory `minibench compare` can
   consume directly.

   Execution is PLUGGABLE by design: the sweep does not link the N2
   execution machine; it runs the configured command template per
   (variant x replicate) — typically `mf workflow execute <spec> ...` or
   `mf chain run <chain> ...` — and requires only that the command leave
   a supervisory PolicyEvaluation EDN at the templated `{out}` path. A
   variant may instead point at a pre-produced `:policy-eval` file
   (re-projection of runs executed elsewhere).

   Methodology (methods review 2026-07-02): replicates share the variant
   LABEL and differ in run/snapshot ids, so the comparison kernel can
   group them and separate within-variant noise from between-variant
   effect. `:replicate` rides in each snapshot's metadata; provenance
   (config digest + eval digest) rides in source_hashes/metadata."
  (:require [ai.miniforge.workbench-adapter.interface :as adapter]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- template

(defn substitute
  "Fill `{placeholder}`s in one argv token from `vars` (keyword keys).
   Throws on an unresolved placeholder — a sweep that silently runs the
   wrong command is worse than one that refuses to run."
  [token vars]
  (str/replace token #"\{([^{}]+)\}"
               (fn [[whole nm]]
                 (let [v (get vars (keyword nm) ::missing)]
                   (if (= v ::missing)
                     (throw (ex-info (str "Unresolved placeholder " whole)
                                     {:token token :available (keys vars)}))
                     (str v))))))

(defn render-command [command vars]
  (mapv #(substitute % vars) command))

;; ---------------------------------------------------------------- execution

(defn- run-command!
  "Run one rendered argv, capturing output. Throws with the exit code and
   the tail of stderr when the command fails."
  [argv]
  (let [{:keys [exit out err]} (apply process/shell
                                      {:out :string :err :string :continue true}
                                      argv)]
    (when-not (zero? exit)
      (throw (ex-info (str "Sweep command failed (exit " exit ")")
                      {:argv argv
                       :exit exit
                       :err-tail (->> (str/split-lines (str err))
                                      (take-last 10)
                                      (str/join "\n"))})))
    {:out out :err err}))

(defn- read-policy-eval [path]
  (edn/read-string {:readers {'uuid parse-uuid
                              'inst #(java.time.Instant/parse %)}}
                   (slurp path)))

;; ---------------------------------------------------------------- one run

(defn- id-stamp [iso]
  (-> iso (str/replace #"\.[0-9]+" "") (str/replace #"[:\-]" "")))

(defn- run-variant
  "The RunVariant map stamped on every replicate of `variant`."
  [experiment-id {:keys [label workflow prompt model method axes]}]
  (cond-> {:experiment_id experiment-id :label label}
    workflow (assoc :workflow workflow)
    prompt (assoc :prompt prompt)
    model (assoc :model model)
    method (assoc :method method)
    (seq axes) (assoc :axes axes)))

(defn execute-one!
  "Execute (or collect) one (variant x replicate) and emit its snapshot.
   Returns the snapshot path."
  [{:keys [experiment-id out-dir registry-version command config-sha]} packs variant replicate]
  (let [label (:label variant)
        eval-path (or (:policy-eval variant)
                      (str (io/file out-dir "evals" (str label "-r" replicate ".edn"))))
        vars (merge (:bindings variant)
                    {:label label :replicate replicate :out eval-path})
        argv (when (and command (not (:policy-eval variant)))
               (render-command command vars))]
    (when argv
      (io/make-parents eval-path)
      (run-command! argv))
    (let [now (str (java.time.Instant/now))
          stamp (id-stamp now)
          snapshot (adapter/policy-eval->snapshot
                    (read-policy-eval eval-path)
                    packs
                    {:snapshot-id (str "wb-miniforge-" label "-r" replicate "-" stamp)
                     :run-id (str "run-" label "-r" replicate "-" stamp)
                     :generated-at now
                     :registry-version registry-version
                     :variant (run-variant experiment-id variant)
                     :source-hashes [(adapter/sha256-file eval-path)]
                     :metadata (cond-> {:emitter "miniforge.workbench-sweep"
                                        :replicate replicate}
                                 config-sha (assoc :sweep_config_sha256 config-sha)
                                 argv (assoc :command (str/join " " argv)))})
          out-path (str (io/file out-dir (str label "-r" replicate ".json")))]
      (adapter/write-snapshot! snapshot out-path)
      out-path)))

;; ---------------------------------------------------------------- the sweep

(defn- clear-stale-snapshots!
  "Delete `*.json` left in `out-dir` by earlier sweeps so the comparison
   matrix is never contaminated by runs outside this experiment."
  [out-dir]
  (doseq [f (.listFiles (io/file out-dir))
          :when (and (.isFile f) (str/ends-with? (.getName f) ".json"))]
    (.delete f)))

(defn run-sweep!
  "Execute the whole matrix: every variant x `:replicates` (default 1),
   sequentially, emitting one snapshot per run into `:out-dir`. Returns
   `{:snapshots [paths] :out-dir ...}`."
  [{:keys [out-dir replicates packs-dir variants] :as config} config-sha]
  (let [packs (adapter/load-packs! (or packs-dir (adapter/builtin-packs-dir)))
        k (or replicates 1)
        ctx (assoc (select-keys config [:experiment-id :out-dir
                                        :registry-version :command])
                   :config-sha config-sha)]
    (.mkdirs (io/file out-dir))
    (clear-stale-snapshots! out-dir)
    (let [paths (vec (for [variant variants
                           replicate (range 1 (inc k))]
                       (execute-one! ctx packs variant replicate)))]
      {:snapshots paths :out-dir out-dir})))
