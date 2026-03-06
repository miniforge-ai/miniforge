(ns ai.miniforge.policy-pack.scanner-protocol
  "Formalized scanner interface for policy-pack rule detection.

   Scanners implement a uniform protocol for detecting violations in artifacts.
   This decouples detection logic from policy-pack rule definitions, enabling:
   - Custom scanner implementations (regex, AST, LLM-powered)
   - Scanner composition (chain multiple scanners)
   - Scanner registration and discovery

   Layer 0: Protocol definitions
   Layer 1: Scanner registry
   Layer 2: Built-in scanners")

;------------------------------------------------------------------------------ Layer 0
;; Protocol definitions

(defprotocol Scanner
  "Protocol for artifact scanning.

   Scanners detect violations in artifacts. Each scanner focuses on a specific
   detection type (content patterns, AST analysis, diff analysis, etc.)."

  (scanner-id [this]
    "Return the unique identifier for this scanner.
     Returns: keyword")

  (scanner-type [this]
    "Return the detection type this scanner handles.
     Returns: #{:content-scan :diff-analysis :plan-output :ast-analysis :custom}")

  (scan [this artifact context]
    "Scan an artifact for violations.

     Arguments:
     - artifact: Map with :content (string or data)
     - context: Map with :phase, :task-type, :rule (the rule being checked)

     Returns: {:violations [{:violation/rule-id string
                              :violation/severity keyword
                              :violation/message string
                              :violation/location map?
                              :violation/remediation string?
                              :violation/auto-fixable? bool?}]
               :scanned? bool
               :scanner-id keyword}"))

(defprotocol RepairableScanner
  "Extension protocol for scanners that can suggest or apply repairs."

  (can-repair? [this violation]
    "Check if this scanner can repair a specific violation.
     Returns: boolean")

  (suggest-repair [this violation artifact context]
    "Suggest a repair for a violation without applying it.
     Returns: {:suggestion string :patch map? :confidence float}")

  (apply-repair [this violation artifact context]
    "Apply a repair for a violation.
     Returns: {:success? bool :artifact map :applied-fix string?}"))

;------------------------------------------------------------------------------ Layer 1
;; Scanner registry

(defonce scanner-registry (atom {}))

(defn register-scanner!
  "Register a scanner in the global registry.

   Arguments:
   - scanner: Instance implementing Scanner protocol

   Returns: scanner-id keyword"
  [scanner]
  (let [id (scanner-id scanner)]
    (swap! scanner-registry assoc id scanner)
    id))

(defn deregister-scanner!
  "Remove a scanner from the registry."
  [scanner-id-kw]
  (swap! scanner-registry dissoc scanner-id-kw)
  nil)

(defn get-scanner
  "Get a scanner by ID from the registry."
  [scanner-id-kw]
  (get @scanner-registry scanner-id-kw))

(defn list-scanners
  "List all registered scanners.
   Returns: [{:id keyword :type keyword}]"
  []
  (mapv (fn [[id scanner]]
          {:id id :type (scanner-type scanner)})
        @scanner-registry))

(defn scanners-for-type
  "Get all scanners that handle a given detection type."
  [detection-type]
  (filterv (fn [[_id scanner]]
             (= detection-type (scanner-type scanner)))
           @scanner-registry))

;------------------------------------------------------------------------------ Layer 2
;; Built-in scanners

(defrecord RegexScanner [id patterns]
  Scanner
  (scanner-id [_] id)
  (scanner-type [_] :content-scan)
  (scan [_ artifact context]
    (let [content (or (:content artifact)
                      (get-in artifact [:artifact/content] ""))
          content-str (if (string? content) content (pr-str content))
          rule (:rule context)
          violations (for [{:keys [pattern-name regex severity message]} patterns
                           :let [matches (re-seq regex content-str)]
                           :when (seq matches)]
                       {:violation/rule-id (or (:rule/id rule) (str (name id) "/" pattern-name))
                        :violation/severity (or severity :medium)
                        :violation/message (or message
                                               (str "Pattern " pattern-name " matched "
                                                    (count matches) " time(s)"))
                        :violation/location {:pattern pattern-name
                                             :match-count (count matches)}})]
      {:violations (vec violations)
       :scanned? true
       :scanner-id id})))

(defn create-regex-scanner
  "Create a regex-based content scanner.

   Arguments:
   - id: Scanner identifier keyword
   - patterns: Vector of {:pattern-name string :regex Pattern :severity keyword :message string?}

   Returns: RegexScanner instance"
  [id patterns]
  (->RegexScanner id patterns))
