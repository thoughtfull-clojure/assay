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
    "Config file (default: .config/assay.edn in the workspace, if present)"]
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
            (io/file workspace-dir ".config" "assay.edn"))]
    (cond
      (.exists f) (edn/read-string (slurp f))
      config (throw (ex-info (str "Config file not found: " config) {}))
      :else {})))

(defn- workspace?
  [dir]
  (.exists (io/file dir "workspace.edn")))

(defn- measure
  "Measure and check the workspace at root."
  [root config]
  (let [rules (thresholds/merge-thresholds (:thresholds config))
        bricks (mapv #(metrics/measure-brick root %) (workspace/bricks root))]
    {:bricks bricks
     :violations (thresholds/check rules bricks)
     :thresholds rules}))

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
              (measure dir config)
              {:bricks [] :violations []})
            head
            (git/changed-files root rev)
            (:changes config))
        (update :comparison assoc :base-ref ref :base-rev rev))
      (finally
        (delete-tree dir)))))

(defn report
  "Measure the workspace at workspace-dir and check it against config,
  comparing with the merge-base of base-ref when it is given. Returns a
  report map for the report components."
  [workspace-dir config base-ref]
  (let [root (.getCanonicalFile (io/file workspace-dir))
        head (measure root config)]
    (-> (if base-ref
          (compare-with-base root config base-ref head)
          head)
      (assoc :workspace (.getName root)
        :generated-at (str (java.time.Instant/now))
        :changes (:changes config)))))

(defn- failed?
  "True if report has an error-level violation within scope, \"new\" or
  \"all\". Without a base, every violation counts as new."
  [report scope]
  (some #(and (= :error (:level %))
           (or (= "all" scope) (#{:new nil} (:status %))))
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

(defn- execute
  [{:keys [workspace config base fail fail-on] :as options} env]
  (let [report (report workspace (read-config workspace config) base)]
    (doseq [format (formats options env)]
      ((writers format) report options env))
    (if (and fail (failed? report (or fail-on (if base "new" "all"))))
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
