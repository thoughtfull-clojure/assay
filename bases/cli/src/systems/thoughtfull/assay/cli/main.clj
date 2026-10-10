(ns systems.thoughtfull.assay.cli.main
  "Command line entry point. Measures every brick of a Polylith workspace,
  checks the measurements against thresholds, optionally compares them with
  a base revision, and writes an HTML report or a GitHub Actions report."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [systems.thoughtfull.assay.baseline.interface :as baseline]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]
   [systems.thoughtfull.assay.git.interface :as git]
   [systems.thoughtfull.assay.github-report.interface :as github-report]
   [systems.thoughtfull.assay.html-report.interface :as html-report]
   [systems.thoughtfull.assay.metrics.interface :as metrics]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]
   [systems.thoughtfull.assay.text-report.interface :as text-report]
   [systems.thoughtfull.assay.workspace.interface :as workspace]))

(def ^:private options
  [["-w" "--workspace DIR" "Polylith workspace root"
    :default "."]
   ["-c" "--config FILE"
    "Config file (default: assay.edn in the workspace, if present)"]
   ["-b" "--base REF"
    (str "Compare with the merge-base of REF and HEAD, such as origin/main,"
      " and fail only on new violations")]
   ["-f" "--format FORMAT"
    (str "Output format: html, github, or text; repeat for more than one"
      " (default: github when GITHUB_ACTIONS is set, otherwise html)")
    :multi true
    :default []
    :update-fn conj
    :validate [#{"html" "github" "text"} "must be html, github, or text"]]
   ["-o" "--output FILE"
    "HTML report file (default: target/assay/index.html in the workspace)"]
   [nil "--fail-on SCOPE"
    (str "Fail on error-level violations that are new or all (default: new"
      " with --base, otherwise all)")
    :validate [#{"new" "all"} "must be new or all"]]
   [nil "--[no-]warnings"
    "Report warning-level violations too (default: errors only)"
    :default false]
   [nil "--[no-]fail" "Exit with status 1 when an error threshold is exceeded"
    :default true]
   ["-h" "--help" "Show this help"]])

(defn- usage
  [summary]
  (str "Usage: assay [options]\n\n"
    "Measure the bricks of a Polylith workspace and check them against\n"
    "thresholds.\n\n"
    "Options:\n" summary))

(defn- read-config
  [workspace-dir config]
  (let [f (if config
            (io/file config)
            (io/file workspace-dir "assay.edn"))]
    (cond
      (.exists f) (edn/read-string (slurp f))
      config (throw (ex-info (str "Config file not found: " config) {}))
      :else {})))

(defn- workspace?
  [dir]
  (.exists (io/file dir "workspace.edn")))

(defn- metric-settings
  "Each metric's merged settings, by metric key."
  [config]
  (into {}
    (for [{:keys [key]} metrics/metrics
          :let [s (thresholds/settings config key)]
          :when s]
      [key s])))

(defn- commits
  "The commits the co-change metric looks at, or nil when it is off or root
  has no Git history to read."
  [root config]
  (let [{:keys [since] :as co-change} (thresholds/settings config
                                        :co-change)]
    (when (or (:warning co-change) (:error co-change))
      (try
        (git/log-files root since)
        (catch clojure.lang.ExceptionInfo _ nil)))))

(defn- measure
  "Measure and check the workspace at root against merged config, with
  commits for the co-change metric."
  [root config commits]
  (let [analysis (-> (dependencies/analyze (workspace/config root)
                       (mapv #(metrics/measure-brick root %)
                         (workspace/bricks root)))
                   (assoc :commits commits))
        settings (cond-> (metric-settings config)
                   (nil? commits) (dissoc :co-change))
        {:keys [bricks violations]} (thresholds/check config
                                      (:bricks analysis)
                                      (dependencies/findings settings
                                        analysis))]
    {:bricks bricks
     :edges (:edges analysis)
     :libraries (:libraries analysis)
     :violations violations
     :thresholds config}))

(defn- temp-dir
  ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory "assay-base"
             (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree
  [^java.io.File f]
  (doseq [^java.io.File child (reverse (file-seq f))]
    (.delete child)))

(defn- compare-with-base
  "Compare head with the merge-base of ref, measured with the same config. A
  base that is not yet a Polylith workspace has no bricks."
  [root config ref head]
  (let [rev (git/merge-base root ref)
        dir (temp-dir)]
    (try
      (git/extract root rev dir)
      (-> (baseline/compare-reports
            (if (workspace? dir)
              (measure dir config nil)
              {:bricks [] :violations []})
            head
            (git/changed-files root rev))
        (update :comparison assoc :base-ref ref :base-rev rev))
      (finally
        (delete-tree dir)))))

(defn report
  "Measure the workspace at workspace-dir and check it against config,
  comparing with the merge-base of base-ref when it is given. Returns a
  report map for the report components."
  [workspace-dir config base-ref]
  (let [root (.getCanonicalFile (io/file workspace-dir))
        config (thresholds/merge-config config)
        head (measure root config (commits root config))]
    (-> (if base-ref
          (compare-with-base root config base-ref head)
          head)
      (assoc :workspace (.getName root)
        :generated-at (str (java.time.Instant/now))))))

(defn- failed?
  "True if report has an error-level violation within scope, \"new\" or
  \"all\". Without a base, every violation counts as new."
  [report scope]
  (some #(and (= :error (:level %))
           (or (= "all" scope) (contains? #{:new nil} (:status %))))
    (:violations report)))

(defn- write-html
  [report {:keys [workspace output]} _env]
  (let [f (io/file (or output
                     (io/file workspace "target" "assay" "index.html")))]
    (io/make-parents f)
    (spit f (html-report/render report))
    (println "Wrote" (str f))))

(defn- write-github
  [report _options env]
  (doseq [line (github-report/annotations report)]
    (println line))
  (if-let [summary-file (env "GITHUB_STEP_SUMMARY")]
    (spit summary-file (github-report/summary report) :append true)
    (println (github-report/summary report))))

(defn- write-text
  [report _options _env]
  (print (text-report/render report))
  (flush))

(def ^:private writers
  "Format name to a function of report, options, and env that writes it."
  {"html" write-html
   "github" write-github
   "text" write-text})

(defn- formats
  [options env]
  (distinct (or (seq (:format options))
              [(if (env "GITHUB_ACTIONS") "github" "html")])))

(defn- hide-warnings
  "Remove warning-level violations from report, counting in
  :hidden-warnings those a change introduced (or all, without a base)."
  [report]
  (let [warning? #(= :warning (:level %))
        warnings (filter warning? (:violations report))]
    (cond-> (assoc report
              :violations (vec (remove warning? (:violations report)))
              :hidden-warnings (count (filter #(contains? #{nil :new} (:status %))
                                        warnings)))
      (:comparison report)
      (update-in [:comparison :resolved] #(vec (remove warning? %))))))

(defn- hide-existing
  "Remove the violations a change didn't introduce from report, counting
  them in :hidden-existing, so the report shows what the change added."
  [report]
  (let [existing? #(contains? #{:existing :indirect} (:status %))]
    (assoc report
      :violations (vec (remove existing? (:violations report)))
      :hidden-existing (count (filter existing? (:violations report))))))

(defn- execute
  "Report on the workspace. The graph draws every violation, in
  :graph-violations, since the lines for co-change and new dependencies
  show structure, not just problems. When only new violations fail, the
  others are left out of the report."
  [{:keys [workspace config base fail fail-on warnings] :as options} env]
  (let [fail-on (or fail-on (if base "new" "all"))
        full (report workspace (read-config workspace config) base)
        report (cond-> (assoc full :graph-violations (:violations full))
                 (not warnings) hide-warnings
                 (and base (= "new" fail-on)) hide-existing)]
    (doseq [format (formats options env)]
      ((writers format) report options env))
    (if (and fail (failed? report fail-on))
      1
      0)))

(defn- error
  [& lines]
  (binding [*out* *err*]
    (println (str/join "\n" lines)))
  2)

(defn run
  "Run with command line args and environment env (a map of variable name to
  value). Prints output and returns the exit status."
  [args env]
  (let [{:keys [options errors summary]} (cli/parse-opts args options)]
    (cond
      (:help options) (do (println (usage summary)) 0)
      (seq errors) (apply error (concat errors [(usage summary)]))
      (not (workspace? (:workspace options)))
      (error (str "Not a Polylith workspace (no workspace.edn): "
               (:workspace options)))
      :else (try
              (execute options env)
              (catch clojure.lang.ExceptionInfo e
                (error (ex-message e)))))))

(defn -main
  [& args]
  (System/exit (run args (into {} (System/getenv)))))
