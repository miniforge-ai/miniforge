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

(ns ai.miniforge.workflow-security-compliance.phases
  "Phase interceptors for the 5-phase security compliance workflow (issue #552).

   Pipeline:
     :sec-parse-scan      — parse SARIF/CSV files into unified violations
     :sec-trace-source    — trace each violation to actual API call (LLM-assisted stub)
     :sec-verify-docs     — verify API documentation status (mixed/stub)
     :sec-classify        — classify violations as true/false/needs-investigation
     :sec-generate-exclusions — generate and write exclusion list

   Context threading:
     Results stored at [:execution/phase-results :phase-keyword :result :output]
     Subsequent phases read from prior phase results at the same path."
  (:require
   [ai.miniforge.phase.phase-result :as phase-result]
   [ai.miniforge.phase.registry     :as registry]
   [cheshire.core    :as json]
   [clojure.java.io  :as io]
   [clojure.string   :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers — SARIF/CSV parsing (inline for prototype; production uses connector-sarif)

(defn- parse-sarif-file
  "Parse a SARIF v2.1.0 JSON file into unified violation records."
  [path]
  (if (.exists (io/file path))
    (let [content (-> path slurp (json/parse-string))
          runs    (get content "runs" [])]
      (into []
            (mapcat
             (fn [[run-idx run]]
               (let [tool-name (get-in run ["tool" "driver" "name"] "unknown")
                     results   (get run "results" [])]
                 (map-indexed
                  (fn [res-idx result]
                    (let [loc  (first (get result "locations" []))
                          phys (get loc "physicalLocation")
                          art  (get phys "artifactLocation")
                          reg  (get phys "region")]
                      {:violation/id          (str tool-name ":" run-idx ":" res-idx)
                       :violation/rule-id     (get result "ruleId" "unknown")
                       :violation/message     (get-in result ["message" "text"] "")
                       :violation/severity    (keyword (get result "level" "warning"))
                       :violation/location    {:file   (get art "uri")
                                               :line   (get reg "startLine")
                                               :column (get reg "startColumn")}
                       :violation/source-tool tool-name
                       :violation/raw         result}))
                  results)))
             (map-indexed vector runs))))
    []))

(defn- parse-csv-file
  "Parse a CSV scan output file into unified violation records."
  [path]
  (if (.exists (io/file path))
    (let [lines (str/split (slurp path) #"\n")]
      (mapv (fn [[_idx line]]
              (let [parts (str/split line #"," 6)
                    [rule-id message severity file line-no col] parts]
                {:violation/id          (str "csv:" rule-id ":" line-no)
                 :violation/rule-id     (str/trim (or rule-id "unknown"))
                 :violation/message     (str/trim (str/replace (or message "") #"\"" ""))
                 :violation/severity    (keyword (str/lower-case (str/trim (or severity "warning"))))
                 :violation/location    {:file   (str/trim (or file ""))
                                         :line   (try (Integer/parseInt (str/trim (or line-no "0")))
                                                      (catch Exception _ 0))
                                         :column (try (Integer/parseInt (str/trim (or col "0")))
                                                      (catch Exception _ 0))}
                 :violation/source-tool "csv-import"
                 :violation/raw         {:line line}}))
            (map-indexed vector (rest lines))))
    []))

(def ^:private known-apis
  "Default set of known/documented public APIs for verification."
  #{"SHCreateStreamOnFileW" "CreateFileW" "ReadFile" "WriteFile"
    "CryptEncrypt" "SetWindowPos" "CoInitializeEx" "OleInitialize"
    "LoadLibraryW" "GetProcAddress" "FreeLibrary"})

(defn- resolve-scan-path
  "Resolve a scan path from either the filesystem or the test classpath.
   Tests run from the repo root, so component-local fixtures need a classpath
   fallback instead of assuming the current working directory."
  [path]
  (let [file (io/file path)]
    (cond
      (.exists file)
      (.getPath file)

      :else
      (some-> (or (io/resource path)
                  (io/resource (str/replace-first path #"^test/" ""))
                  (io/resource (str/replace-first path #"^test/fixtures/" "fixtures/")))
              io/file
              .getPath))))

;------------------------------------------------------------------------------ Layer 0
;; Default configs

(def default-parse-scan-config
  {:agent nil :gates [] :budget {:tokens 1000 :iterations 1 :time-seconds 60}})

(def default-trace-source-config
  {:agent :security-analyst :gates [] :budget {:tokens 50000 :iterations 5 :time-seconds 600}})

(def default-verify-docs-config
  {:agent :security-analyst :gates [] :budget {:tokens 10000 :iterations 1 :time-seconds 300}})

(def default-classify-config
  {:agent nil :gates [:classification-gate] :budget {:tokens 5000 :iterations 1 :time-seconds 120}})

(def default-generate-exclusions-config
  {:agent nil :gates [] :budget {:tokens 1000 :iterations 1 :time-seconds 60}})

;; Register defaults on load
(registry/register-phase-defaults! :sec-parse-scan           default-parse-scan-config)
(registry/register-phase-defaults! :sec-trace-source         default-trace-source-config)
(registry/register-phase-defaults! :sec-verify-docs          default-verify-docs-config)
(registry/register-phase-defaults! :sec-classify             default-classify-config)
(registry/register-phase-defaults! :sec-generate-exclusions  default-generate-exclusions-config)

;------------------------------------------------------------------------------ Layer 1
;; Phase 1: :sec-parse-scan — mechanical SARIF/CSV parsing

(defn enter-sec-parse-scan
  "Parse SARIF/CSV files from input paths into unified violations."
  [ctx]
  (let [start-time  (System/currentTimeMillis)
        scan-paths  (get-in ctx [:execution/input :scan-paths] [])
        violations  (into []
                         (mapcat
                          (fn [path]
                            (let [resolved-path (or (resolve-scan-path path) path)]
                              (cond
                                (str/ends-with? resolved-path ".sarif") (parse-sarif-file resolved-path)
                                (str/ends-with? resolved-path ".csv")   (parse-csv-file resolved-path)
                                :else [])))
                          scan-paths))]
    (phase-result/enter-context ctx :sec-parse-scan nil [] default-parse-scan-config start-time
                                {:status :success :output {:violations violations}})))

(defn leave-sec-parse-scan [ctx]
  (let [start-time      (get-in ctx [:phase :started-at])
        end-time        (System/currentTimeMillis)
        duration-ms     (- end-time start-time)
        violations      (get-in ctx [:phase :result :output :violations] [])
        violation-count (count violations)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:violation-count violation-count :duration-ms duration-ms})
        (assoc-in [:execution/phase-results :sec-parse-scan :result] (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :sec-parse-scan)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-sec-parse-scan [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; Phase 2: :sec-trace-source — LLM-assisted source tracing (stub)

(defn- stub-trace-violation
  "Stub: enrich a violation with mock trace data.
   In production, this calls the :security-analyst agent to trace
   through wrapper functions and identify the actual API call."
  [violation]
  (let [msg (:violation/message violation)
        dynamic-load? (boolean (re-find #"(?i)loadlibrary|getprocaddress|dynamic.load" (or msg "")))
        actual-api (or (second (re-find #"!([A-Za-z0-9_]+)" (or msg "")))
                       (:violation/rule-id violation))]
    (assoc violation
           :trace/actual-api     actual-api
           :trace/call-chain     [(get-in violation [:violation/location :file] "unknown")]
           :trace/dynamic-load?  dynamic-load?)))

(defn enter-sec-trace-source [ctx]
  (let [start-time  (System/currentTimeMillis)
        violations  (get-in ctx [:execution/phase-results :sec-parse-scan :result :output :violations] [])
        traced      (mapv stub-trace-violation violations)]
    (phase-result/enter-context ctx :sec-trace-source :security-analyst [] default-trace-source-config start-time
                                {:status :success :output {:traced-violations traced}})))

(defn leave-sec-trace-source [ctx]
  (let [start-time  (get-in ctx [:phase :started-at])
        end-time    (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        traced      (get-in ctx [:phase :result :output :traced-violations] [])]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:traced-count (count traced) :duration-ms duration-ms})
        (assoc-in [:execution/phase-results :sec-trace-source :result] (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :sec-trace-source)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-sec-trace-source [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; Phase 3: :sec-verify-docs — mixed mechanical/semantic doc verification (stub)

(defn- verify-violation-docs
  "Verify whether a traced API is documented/public.
   Mechanical: check against known-apis set.
   Semantic (stub): would call agent for ambiguous cases."
  [violation apis]
  (let [actual-api (:trace/actual-api violation "")
        is-known   (boolean (some #(str/includes? actual-api %) apis))]
    (assoc violation
           :verified/documented? is-known
           :verified/public?     is-known
           :verified/by          (if is-known :known-apis-registry :stub-agent))))

(defn enter-sec-verify-docs [ctx]
  (let [start-time (System/currentTimeMillis)
        traced     (get-in ctx [:execution/phase-results :sec-trace-source :result :output :traced-violations] [])
        apis       (get-in ctx [:execution/input :known-apis] known-apis)
        verified   (mapv #(verify-violation-docs % apis) traced)]
    (phase-result/enter-context ctx :sec-verify-docs :security-analyst [] default-verify-docs-config start-time
                                {:status :success :output {:verified-violations verified}})))

(defn leave-sec-verify-docs [ctx]
  (let [start-time  (get-in ctx [:phase :started-at])
        end-time    (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        verified    (get-in ctx [:phase :result :output :verified-violations] [])
        documented  (count (filter :verified/documented? verified))]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:verified-count (count verified)
                                     :documented-count documented
                                     :duration-ms duration-ms})
        (assoc-in [:execution/phase-results :sec-verify-docs :result] (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :sec-verify-docs)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-sec-verify-docs [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; Phase 4: :sec-classify — deterministic classification

(defn- classify-violation
  "Classify a verified violation using deterministic rules.
   Uses doc-status, dynamic-load flag, and severity to determine category."
  [violation]
  (let [is-documented? (get violation :verified/documented? false)
        dynamic-load?  (get violation :trace/dynamic-load? false)
        severity       (:violation/severity violation)]
    (assoc violation
           :classification/category
           (cond
             ;; Documented, non-dynamic → likely false positive
             (and is-documented? (not dynamic-load?))
             :false-positive

             ;; Undocumented + error severity → true positive
             (and (not is-documented?) (= severity :error))
             :true-positive

             ;; Dynamic load with documentation → needs investigation
             (and dynamic-load? is-documented?)
             :needs-investigation

             ;; Default: needs investigation
             :else :needs-investigation)

           :classification/confidence
           (cond
             (and is-documented? (not dynamic-load?)) 0.9
             (and (not is-documented?) (= severity :error)) 0.85
             :else 0.5)

           :classification/doc-status
           (if is-documented? :documented :undocumented)

           :classification/reasoning
           (cond
             (and is-documented? (not dynamic-load?))
             "Documented public API, no dynamic loading — benign"

             (and (not is-documented?) (= severity :error))
             "Undocumented API with error severity — real violation"

             :else
             "Ambiguous — requires manual review"))))

(defn enter-sec-classify [ctx]
  (let [start-time (System/currentTimeMillis)
        verified   (get-in ctx [:execution/phase-results :sec-verify-docs :result :output :verified-violations] [])
        classified (mapv classify-violation verified)]
    (phase-result/enter-context ctx :sec-classify nil [:classification-gate] default-classify-config start-time
                                {:status :success :output {:classified-violations classified}})))

(defn leave-sec-classify [ctx]
  (let [start-time      (get-in ctx [:phase :started-at])
        end-time        (System/currentTimeMillis)
        duration-ms     (- end-time start-time)
        classified      (get-in ctx [:phase :result :output :classified-violations] [])
        true-positives  (count (filter #(= :true-positive (:classification/category %)) classified))
        false-positives (count (filter #(= :false-positive (:classification/category %)) classified))
        needs-review    (count (filter #(= :needs-investigation (:classification/category %)) classified))]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:true-positives  true-positives
                                     :false-positives false-positives
                                     :needs-review    needs-review
                                     :duration-ms     duration-ms})
        (assoc-in [:execution/phase-results :sec-classify :result] (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :sec-classify)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-sec-classify [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; Phase 5: :sec-generate-exclusions — mechanical output

(defn enter-sec-generate-exclusions [ctx]
  (let [start-time  (System/currentTimeMillis)
        classified  (get-in ctx [:execution/phase-results :sec-classify :result :output :classified-violations] [])
        excluded    (filter #(= :false-positive (:classification/category %)) classified)
        flagged     (filter #(not= :false-positive (:classification/category %)) classified)
        excl-map    (reduce (fn [acc v]
                              (update acc (:violation/rule-id v)
                                      (fnil conj [])
                                      {:file           (get-in v [:violation/location :file])
                                       :line           (get-in v [:violation/location :line])
                                       :classification (:classification/category v)
                                       :justification  (:classification/reasoning v)
                                       :api            (:trace/actual-api v)
                                       :documented?    (:verified/documented? v)}))
                            {}
                            excluded)
        output-dir  (get-in ctx [:execution/input :output-dir] ".")
        excl-dir    (io/file output-dir ".security-exclusions")]
    ;; Write exclusion file
    (.mkdirs excl-dir)
    (spit (io/file excl-dir "exclusions.edn")
          (pr-str {:generated-at (str (java.time.Instant/now))
                   :exclusions   excl-map
                   :summary      {:total-excluded (count excluded)
                                  :total-flagged  (count flagged)}}))
    (phase-result/enter-context ctx :sec-generate-exclusions nil [] default-generate-exclusions-config start-time
                                {:status :success
                                 :output {:exclusion-list  excl-map
                                          :total-excluded  (count excluded)
                                          :total-flagged   (count flagged)}})))

(defn leave-sec-generate-exclusions [ctx]
  (let [start-time  (get-in ctx [:phase :started-at])
        end-time    (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        output      (get-in ctx [:phase :result :output])]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:excluded  (:total-excluded output 0)
                                     :flagged   (:total-flagged output 0)
                                     :duration-ms duration-ms})
        (assoc-in [:execution/phase-results :sec-generate-exclusions :result] (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :sec-generate-exclusions)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-sec-generate-exclusions [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod registry/get-phase-interceptor :sec-parse-scan
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::sec-parse-scan
     :config merged
     :enter  (fn [ctx] (enter-sec-parse-scan (assoc ctx :phase-config merged)))
     :leave  leave-sec-parse-scan
     :error  error-sec-parse-scan}))

(defmethod registry/get-phase-interceptor :sec-trace-source
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::sec-trace-source
     :config merged
     :enter  (fn [ctx] (enter-sec-trace-source (assoc ctx :phase-config merged)))
     :leave  leave-sec-trace-source
     :error  error-sec-trace-source}))

(defmethod registry/get-phase-interceptor :sec-verify-docs
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::sec-verify-docs
     :config merged
     :enter  (fn [ctx] (enter-sec-verify-docs (assoc ctx :phase-config merged)))
     :leave  leave-sec-verify-docs
     :error  error-sec-verify-docs}))

(defmethod registry/get-phase-interceptor :sec-classify
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::sec-classify
     :config merged
     :enter  (fn [ctx] (enter-sec-classify (assoc ctx :phase-config merged)))
     :leave  leave-sec-classify
     :error  error-sec-classify}))

(defmethod registry/get-phase-interceptor :sec-generate-exclusions
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::sec-generate-exclusions
     :config merged
     :enter  (fn [ctx] (enter-sec-generate-exclusions (assoc ctx :phase-config merged)))
     :leave  leave-sec-generate-exclusions
     :error  error-sec-generate-exclusions}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/phase-defaults :sec-parse-scan)
  (registry/phase-defaults :sec-trace-source)
  (registry/phase-defaults :sec-verify-docs)
  (registry/phase-defaults :sec-classify)
  (registry/phase-defaults :sec-generate-exclusions))
