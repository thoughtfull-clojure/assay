(ns systems.thoughtfull.assay.cli.main-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.cli.main :as main]))

(defn- workspace
  []
  (let [root (doto (java.io.File/createTempFile "assay" "")
               (.delete)
               (.mkdirs))]
    (spit (io/file root "workspace.edn") "{}")
    (doto (io/file root "components/simple/src/simple.clj")
      (io/make-parents)
      (spit "(ns simple)\n(defn f [x] x)\n"))
    (doto (io/file root "components/branchy/src/branchy.clj")
      (io/make-parents)
      (spit "(ns branchy)\n(defn g [a b] (if a (when b 1) (or a b)))\n"))
    root))

(defn- run
  [args env]
  (let [out (java.io.StringWriter.)
        status (binding [*out* out
                         *err* out]
                 (main/run args env))]
    [status (str out)]))

(deftest html-test
  (let [root (workspace)
        [status out] (run ["-w" (str root)] {})]
    (is (= 0 status))
    (is (str/includes? out "Wrote"))
    (is (.exists (io/file root "target/assay/index.html")))))

(deftest github-test
  (let [root (workspace)
        summary (io/file root "summary.md")]
    (doto (io/file root "assay.edn")
      (io/make-parents)
      (spit "{:function-thresholds {:complexity [{:rule :max :value 2}]}}"))
    (testing "defaults to github format in GitHub Actions"
      (let [[status out] (run ["-w" (str root)]
                           {"GITHUB_ACTIONS" "true"
                            "GITHUB_STEP_SUMMARY" (str summary)})]
        (is (= 1 status))
        (is (str/includes? out "::error file=components/branchy/src/branchy.clj,line=2"))
        (is (str/includes? (slurp summary) "## Assay:"))))
    (testing "--no-fail"
      (is (= 0 (first (run ["-w" (str root) "-f" "github" "--no-fail"] {})))))))

(deftest errors-test
  (is (= 2 (first (run ["-f" "pdf"] {}))))
  (is (= 2 (first (run ["-w" "/nonexistent"] {}))))
  (is (= 0 (first (run ["--help"] {}))))
  (let [[status out] (run ["-w" (str (workspace)) "-b" "main"] {})]
    (is (= 2 status))
    (is (str/includes? out "git merge-base main HEAD failed"))))

(defn- sh!
  [dir & args]
  (let [{:keys [exit err out]} (apply clojure.java.shell/sh
                                 (concat args [:dir dir]))]
    (assert (zero? exit) err)
    out))

(defn- commit!
  [root message]
  (sh! root "git" "add" "-A")
  (sh! root "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c"
    "commit.gpgsign=false" "commit" "-q" "--no-verify" "-m" message))

(deftest base-test
  (let [root (workspace)
        config "{:function-thresholds {:complexity [{:rule :max :value 2}]}}"]
    (doto (io/file root "assay.edn")
      (io/make-parents)
      (spit config))
    (sh! root "git" "init" "-q" "-b" "main")
    (commit! root "base")
    (sh! root "git" "checkout" "-q" "-b" "feature")
    (testing "existing violations do not fail, and are not shown"
      (let [[status out] (run ["-w" (str root) "-f" "github" "-b" "main"] {})]
        (is (= 0 status))
        (is (not (str/includes? out "::notice")))
        (is (str/includes? out
              "1 existing or indirect violation not shown"))))
    (testing "--fail-on all fails on existing violations, and shows them"
      (let [[status out] (run ["-w" (str root) "-f" "github" "-b" "main"
                               "--fail-on" "all"]
                           {})]
        (is (= 1 status))
        (is (str/includes? out "::notice"))))
    (testing "new violations in changed bricks fail"
      (spit (io/file root "components/simple/src/simple.clj")
        "(ns simple)\n(defn f [x] (if x (when x 1) 2))\n")
      (let [[status out] (run ["-w" (str root) "-f" "github" "-b" "main"] {})]
        (is (= 1 status))
        (is (str/includes? out "::error file=components/simple/src/simple.clj"))))))

(defn- configure
  [root config]
  (doto (io/file root "assay.edn")
    (io/make-parents)
    (spit config)))

(deftest warnings-test
  (let [root (workspace)]
    (configure root (str "{:function-thresholds"
                      " {:params [{:rule :max :value 0 :level :warning}]}}"))
    (testing "hidden by default, but counted"
      (let [[status out] (run ["-w" (str root) "-f" "text"] {})]
        (is (= 0 status))
        (is (not (str/includes? out "warning ")))
        (is (str/includes? out "2 warnings hidden"))))
    (testing "shown with --warnings"
      (let [[_ out] (run ["-w" (str root) "-f" "text" "--warnings"] {})]
        (is (str/includes? out "warning components/"))
        (is (str/includes? out ", 2 warnings"))))))

(deftest fail-on-new-without-base-test
  (let [root (workspace)]
    (configure root
      "{:function-thresholds {:complexity [{:rule :max :value 2}]}}")
    (is (= 1 (first (run ["-w" (str root) "-f" "text" "--fail-on" "new"] {})))
      "without a base, every violation is new")))

(deftest misplaced-rule-test
  (let [root (workspace)]
    (configure root "{:dependency-rules {:mutable-state nil}}")
    (is (= 2 (first (run ["-w" (str root) "-f" "text"] {})))
      "a rule under the wrong group is a usage error")))

(deftest config-location-test
  (let [root (workspace)]
    (testing "assay.edn at the workspace root is the default"
      (spit (io/file root "assay.edn")
        "{:function-thresholds {:complexity [{:rule :max :value 2}]}}")
      (is (= 1 (first (run ["-w" (str root) "-f" "text"] {})))))
    (testing ".config/assay.edn is not read unless passed with --config"
      (.delete (io/file root "assay.edn"))
      (doto (io/file root ".config/assay.edn")
        (io/make-parents)
        (spit "{:function-thresholds {:complexity [{:rule :max :value 2}]}}"))
      (is (= 0 (first (run ["-w" (str root) "-f" "text"] {}))))
      (is (= 1 (first (run ["-w" (str root) "-f" "text"
                            "-c" (str (io/file root ".config/assay.edn"))]
                        {})))))))
