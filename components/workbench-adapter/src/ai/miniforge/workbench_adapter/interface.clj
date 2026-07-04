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

(ns ai.miniforge.workbench-adapter.interface
  "Public API of the miniforge workbench adapter: project miniforge
   governance outputs (supervisory PolicyEvaluation records + policy-pack
   manifests) into Minibench `workbench_snapshot/v1` maps, and generate
   the miniforge `state_var_registry/v1` from the packs themselves.

   Boundary discipline: inputs are validated against the supervisory-state
   PolicyEvaluation schema; emitted wire maps are validated against this
   component's CLOSED emit schemas (schema.clj). The canonical wire
   contract lives in the external `workbench-contract` repo, whose test
   suite round-trips miniforge-emitted golden fixtures — that suite, not
   this component, is the cross-repo contract gate."
  (:require [ai.miniforge.policy-pack.interface :as policy-pack]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workbench-adapter.core :as core]
            [ai.miniforge.workbench-adapter.schema :as schema]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn- validate!
  [kind malli-schema value]
  (if (m/validate malli-schema value)
    value
    (throw (ex-info (str "Invalid " (name kind))
                    {:kind kind
                     :errors (me/humanize (m/explain malli-schema value))}))))

(defn load-packs!
  "Load every policy pack in `dir` via the policy-pack loader. Returns the
   vector of pack manifests; throws when any pack fails to load, because a
   registry generated from a partial pack set would silently shrink the
   state-variable universe."
  [dir]
  (let [{:keys [loaded failed]} (policy-pack/load-all-packs dir)]
    (when (seq failed)
      (throw (ex-info "Policy pack(s) failed to load"
                      {:kind :policy-pack-load :failed failed})))
    (vec loaded)))

(defn packs->registry
  "Generate the miniforge `state_var_registry/v1` from loaded pack
   manifests: one state variable per rule, a pass_rate per pack, and the
   cross-pack critical-violations aggregate. `version` is the registry
   DateVer. Validates the emitted registry."
  [packs version]
  (validate! :state-var-registry schema/StateVarRegistry
             (core/packs->registry packs version)))

(defn merge-policy-evals
  "Merge several PolicyEvaluations (one per gate of a run) into one
   aggregate record for projection — violations/packs unioned, passed?
   conjoined, latest evaluated-at, fresh id. Single element passes
   through unchanged."
  [evals]
  (core/merge-policy-evals evals))

(defn policy-eval->snapshot
  "Project a supervisory PolicyEvaluation into a validated
   `workbench_snapshot/v1`. `opts`:
   `{:snapshot-id :run-id :generated-at :registry-version}` required;
   `:variant` (RunVariant map — tags the run for `minibench compare`),
   `:source-hashes`, `:metadata` optional provenance.
   Throws when the input or the emitted snapshot is invalid."
  [policy-eval packs opts]
  (validate! :policy-evaluation supervisory/PolicyEvaluation policy-eval)
  (validate! :workbench-snapshot schema/WorkbenchSnapshot
             (core/policy-eval->snapshot policy-eval packs opts)))

(defn valid-snapshot?
  "True when `snapshot` conforms to the emit-side wire schema."
  [snapshot]
  (m/validate schema/WorkbenchSnapshot snapshot))

(defn sha256-file
  "`sha256:<hex>` digest of a file's bytes — `source_hashes` provenance."
  [path]
  (str "sha256:"
       (format "%064x"
               (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                       (java.nio.file.Files/readAllBytes
                                        (.toPath (io/file (str path)))))))))

(defn- parse-ref
  "`\"id@version\"` -> `{:id \"id\" :version \"version\"}`; bare id ok."
  [s]
  (let [[id version] (str/split (str s) #"@" 2)]
    (cond-> {:id id} version (assoc :version version))))

(defn- write-json! [value path]
  (some-> (io/file path) .getParentFile .mkdirs)
  (spit path (json/generate-string value {:pretty true}))
  path)

(defn write-registry!
  "Pretty-print a generated registry to `path` as JSON. Returns the path."
  [registry path]
  (write-json! registry path))

(defn write-snapshot!
  "Pretty-print a snapshot to `path` as JSON. Returns the path."
  [snapshot path]
  (write-json! snapshot path))

;; ---------------------------------------------------------------- clojure -X

(def ^:private builtin-packs-dir*
  (delay (-> (io/resource "policy_pack/packs/foundations-1.0.0.pack.edn")
             io/file .getParentFile str)))

(defn builtin-packs-dir
  "The builtin policy packs directory on the classpath, resolved via a
   known pack resource so the path holds wherever the workspace is
   checked out."
  []
  @builtin-packs-dir*)

(defn- today-datever []
  (str (str/replace (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)) "-" ".") ".1"))

(defn emit-registry!
  "`clojure -X` entry: generate the registry from a packs directory.
   Args (all optional): `:packs-dir` (default: the builtin packs),
   `:version` (default: today's DateVer `YYYY.MM.DD.1`),
   `:out` (default: `/tmp/miniforge-state-vars.json`)."
  [{:keys [packs-dir version out]}]
  (let [packs (load-packs! (str (or packs-dir (builtin-packs-dir))))
        registry (packs->registry packs (str (or version (today-datever))))
        out (str (or out "/tmp/miniforge-state-vars.json"))]
    (write-registry! registry out)
    (println "wrote" out "-" (count (:state_vars registry)) "state variables"))
  nil)

(defn emit-snapshot!
  "`clojure -X` entry: project a PolicyEvaluation EDN file into a
   snapshot. Args: `{:policy-eval \"eval.edn\" :packs-dir \"...\"
   :registry-version \"...\" :out \"...\"}` plus optional
   `:snapshot-id :run-id :experiment-id :label :model :method`,
   `:workflow`/`:prompt` (versioned refs as `\"id@version\"` strings —
   workflow-chain and prompt permutations are first-class variant axes),
   and `:axes` (an EDN map of any further axes)."
  [{:keys [policy-eval packs-dir registry-version out
           snapshot-id run-id experiment-id label model method
           workflow prompt axes]}]
  (let [parsed (edn/read-string {:readers {'uuid parse-uuid
                                           'inst #(java.time.Instant/parse %)}}
                                (slurp (str policy-eval)))
        ;; `mf chain run --policy-eval-out` writes a VECTOR (one record
        ;; per gate); a bare map is accepted for hand-rolled inputs.
        pe (if (sequential? parsed) (merge-policy-evals (vec parsed)) parsed)
        packs (load-packs! (str (or packs-dir (builtin-packs-dir))))
        now (str (java.time.Instant/now))
        stamp (-> now (str/replace #"\.[0-9]+" "") (str/replace #"[:\-]" ""))
        label (some-> label str)
        snapshot (policy-eval->snapshot
                  pe packs
                  (cond-> {:snapshot-id (or (some-> snapshot-id str)
                                            (str "wb-miniforge-" (or label "run") "-" stamp))
                           :run-id (or (some-> run-id str)
                                       (str "run-" (or label "run") "-" stamp))
                           :generated-at now
                           :registry-version (str registry-version)
                           :source-hashes [(sha256-file policy-eval)]
                           :metadata {:emitter "miniforge.workbench-adapter"}}
                    (and experiment-id label)
                    (assoc :variant
                           (cond-> {:experiment_id (str experiment-id)
                                    :label label}
                             model (assoc :model (str model))
                             method (assoc :method (str method))
                             workflow (assoc :workflow (parse-ref workflow))
                             prompt (assoc :prompt (parse-ref prompt))
                             (seq axes) (assoc :axes (update-keys axes keyword))))))]
    (write-snapshot! snapshot (str out))
    (println "wrote" (str out) "-" (count (:evaluations snapshot)) "evaluations"))
  nil)
