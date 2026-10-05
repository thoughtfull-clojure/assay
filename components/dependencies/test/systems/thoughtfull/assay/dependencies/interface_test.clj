(ns systems.thoughtfull.assay.dependencies.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]))

(def ^:private workspace
  {:top-namespace "t" :interface-ns "interface"})

(defn- source
  "A source file of size forms, with a definition for every 10 forms."
  [ns forms & requires]
  {:file (str ns ".clj")
   :ns ns
   :forms forms
   :definitions (vec (for [i (range (quot forms 10))]
                       {:name (symbol (str "d" i)) :line 1 :references #{}}))
   :requires (mapv (fn [r] {:ns r :line 3}) requires)})

(defn- brick-forms
  "bricks with each brick's :forms metric, the sum of its sources'."
  [bricks]
  (mapv #(assoc-in % [:metrics :forms] (reduce + (map :forms (:sources %))))
    bricks))

(defn- brick
  [name type & sources]
  {:brick {:name name :type type}
   :metrics {}
   :sources (vec sources)})

(def ^:private acyclic
  ;; a -> b, c -> a, c -> b, x -> c
  [(brick "a" :component
     (source 't.a.interface 10)
     (source 't.a.core 90 't.b.interface 'clojure.string))
   (brick "b" :component
     (source 't.b.interface 50)
     (source 't.b.core 50 't.b.interface))
   (brick "c" :component
     (source 't.c.interface 25 't.c.core)
     (source 't.c.core 75 't.a.interface 't.b.interface 't.b.interface))
   (brick "x" :base
     (source 't.x.main 10 't.c.interface))])

(defn- metrics-by-name
  [analysis]
  (into {} (map (juxt (comp :name :brick) :metrics)) (:bricks analysis)))

(deftest analyze-test
  (let [analysis (dependencies/analyze workspace acyclic)
        m (metrics-by-name analysis)]
    (testing "edges, one per required interface, at the first require"
      (is (= #{["a" "b"] ["c" "a"] ["c" "b"] ["x" "c"]}
            (set (map (juxt :from :to) (:edges analysis)))))
      (is (= {:file "t.c.core.clj" :line 3}
            (:location (first (filter #(= ["c" "b"] [(:from %) (:to %)])
                                (:edges analysis)))))))
    (testing "afferent and efferent"
      (is (= {"a" [1 1] "b" [2 0] "c" [1 2] "x" [0 1]}
            (update-vals m (juxt :afferent :efferent)))))
    (testing "instability"
      (is (= 0.5 (get-in m ["a" :instability])))
      (is (= 0.0 (get-in m ["b" :instability])))
      (is (= 1.0 (get-in m ["x" :instability]))))
    (testing "abstractness: 1 - interface definitions / all definitions;
              bases are 0"
      (is (= 0.9 (get-in m ["a" :abstractness])))
      (is (= 0.5 (get-in m ["b" :abstractness])))
      (is (= 0.0 (get-in m ["x" :abstractness]))))
    (testing "no violations"
      (is (empty? (dependencies/check {} analysis))))))

(deftest isolated-brick-test
  (let [m (metrics-by-name
            (dependencies/analyze workspace
              [(brick "lonely" :component (source 't.lonely.interface 10))]))]
    (is (= {:afferent 0 :efferent 0 :instability nil :abstractness 0.0
            :cohesion nil :shared-keywords 0 :libraries 0 :shared-libraries 0
            :error-surface 0 :untested-interface 1}
          (m "lonely")))))

(deftest check-test
  (let [;; b now requires c, so b (stable) depends on c (less stable)
        bricks (assoc-in acyclic [1 :sources 1 :requires]
                 [{:ns 't.c.interface :line 9}])
        analysis (dependencies/analyze workspace bricks)
        violations (dependencies/check {} analysis)
        by-metric (group-by :metric violations)]
    (testing "stable dependencies"
      (is (= [["b" "c" :error {:file "t.b.core.clj" :line 9}]]
            (map (juxt (comp :name :brick) :subject :level :location)
              (:stable-dependencies by-metric))))
      (is (re-find #"depends on c \(instability 0\.5\), which is less stable than b \(0\.33\)"
            (:message (first (:stable-dependencies by-metric))))))
    (testing "rules can be turned off or downgraded"
      (is (empty? (dependencies/check {:stable-dependencies nil} analysis)))
      (is (every? #(= :warning (:level %))
            (dependencies/check {:stable-dependencies :warning} analysis))))))

(def ^:private libraried
  ;; a and b both use the database driver; a also uses clojure.string,
  ;; which doesn't count, and its own workspace interface. x is a base.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :sources [{:file "a/core.clj" :ns 't.a.core
               :requires [{:ns 'next.jdbc :line 3} {:ns 'next.jdbc.sql :line 4}
                          {:ns 'clojure.string :line 5}
                          {:ns 't.b.interface :line 6}]}]}
   {:brick {:name "b" :type :component}
    :metrics {}
    :sources [{:file "b/core.clj" :ns 't.b.core
               :requires [{:ns 'next.jdbc.sql :line 3}
                          {:ns 'clojure.java.io :line 4}]}]}
   {:brick {:name "x" :type :base}
    :metrics {}
    :sources [{:file "x/main.clj" :ns 't.x.main
               :requires [{:ns 'clojure.tools.cli :line 3}
                          {:ns 'clojure.java.shell :line 4}]}]}])

(deftest library-spread-test
  (let [analysis (dependencies/analyze workspace libraried)
        m (metrics-by-name analysis)]
    (testing "libraries named by their namespaces"
      (is (= [{:library "next.jdbc" :bricks ["a" "b"]}
              {:library "clojure.java.io" :bricks ["b"]}
              {:library "clojure.java.shell" :bricks ["x"]}
              {:library "clojure.tools.cli" :bricks ["x"]}]
            (map #(select-keys % [:library :bricks]) (:libraries analysis)))))
    (testing "metrics"
      (is (= {"a" [1 1] "b" [2 1] "x" [2 0]}
            (update-vals m (juxt :libraries :shared-libraries)))))
    (testing "a violation at each brick's first require"
      (is (= [["a" "next.jdbc" {:file "a/core.clj" :line 3}
               "requires next.jdbc, which b also requires"]
              ["b" "next.jdbc" {:file "b/core.clj" :line 3}
               "requires next.jdbc, which a also requires"]]
            (->> (dependencies/check {} analysis)
              (filter #(= :library-spread (:metric %)))
              (map (juxt (comp :name :brick) :subject :location :message)))))
      (is (empty? (filter #(= :library-spread (:metric %))
                    (dependencies/check {:library-spread {:max-bricks 2}}
                      analysis)))))))

(deftest mutable-state-test
  (let [state [{:name 'cache :line 2 :kind :atom}]
        violations (->> (dependencies/check {}
                          (dependencies/analyze workspace
                            [{:brick {:name "a" :type :component}
                              :metrics {}
                              :sources [{:file "a/core.clj" :ns 't.a.core
                                         :mutable-state state}
                                        {:file "a/interface.clj"
                                         :ns 't.a.interface
                                         :mutable-state state}]}
                             {:brick {:name "x" :type :base}
                              :metrics {}
                              :sources [{:file "x/main.clj" :ns 't.x.main
                                         :mutable-state state}]}]))
                     (filter #(= :mutable-state (:metric %))))]
    (is (= [["a" {:file "a/core.clj" :line 2 :name "cache"}
             "defines an atom, state hidden from the functions that use it"]
            ["a" {:file "a/interface.clj" :line 2 :name "cache"}
             (str "defines an atom in its interface, so every brick that"
               " uses it shares the state")]]
          (map (juxt (comp :name :brick) :location :message) violations))
      "components only: a base is the shell")))

(deftest error-surface-test
  ;; a's interface delegates to core, whose f calls b's interface, whose
  ;; impl throws. a's g throws nothing.
  (let [defn* (fn [name throws? & refs]
                {:name name :line 1 :references (set refs) :throws? throws?})
        m (metrics-by-name
            (dependencies/analyze workspace
              [{:brick {:name "a" :type :component}
                :metrics {}
                :sources [{:ns 't.a.interface :file "a/interface.clj"
                           :requires [{:ns 't.a.core :as 'core}]
                           :definitions [(defn* 'f false 'core/f)
                                         (defn* 'g false 'core/g)]}
                          {:ns 't.a.core :file "a/core.clj"
                           :requires [{:ns 't.b.interface :as 'b}]
                           :definitions [(defn* 'f false 'b/h)
                                         (defn* 'g false 'inc)]}]}
               {:brick {:name "b" :type :component}
                :metrics {}
                :sources [{:ns 't.b.interface :file "b/interface.clj"
                           :requires [{:ns 't.b.core :refer ['impl]}]
                           :definitions [(defn* 'h false 'impl)]}
                          {:ns 't.b.core :file "b/core.clj" :requires []
                           :definitions [(defn* 'impl true)]}]}
               {:brick {:name "x" :type :base}
                :metrics {}
                :sources [{:ns 't.x.main :file "x/main.clj" :requires []
                           :definitions [(defn* '-main true)]}]}]))]
    (is (= {"a" 1 "b" 1 "x" nil} (update-vals m :error-surface))
      "across bricks, through referred and aliased symbols; bases have none")))

(deftest broad-catch-test
  (is (= [["a" {:file "a/core.clj" :line 4}
           "catches Exception, deciding for every caller what a failure means"]]
        (->> (dependencies/check {}
               (dependencies/analyze workspace
                 [{:brick {:name "a" :type :component}
                   :metrics {}
                   :sources [{:file "a/core.clj" :ns 't.a.core
                              :catches [{:line 4 :class "Exception" :broad? true}
                                        {:line 9 :class "clojure.lang.ExceptionInfo"
                                         :broad? false}]}]}
                  {:brick {:name "x" :type :base}
                   :metrics {}
                   :sources [{:file "x/main.clj" :ns 't.x.main
                              :catches [{:line 2 :class "Throwable"
                                         :broad? true}]}]}]))
          (filter #(= :broad-catch (:metric %)))
          (map (juxt (comp :name :brick) :location :message))))
    "components only: bases are where catching belongs"))

(deftest tests-test
  (let [defs (fn [& names] (vec (for [n names] {:name n :line 1})))
        analysis (dependencies/analyze workspace
                   [{:brick {:name "a" :type :component}
                     :metrics {}
                     :sources [{:ns 't.a.interface :file "a/interface.clj"
                                :requires [] :definitions (defs 'f 'g 'h)}
                               {:ns 't.a.core :file "a/core.clj" :requires []
                                :definitions (defs 'impl)}]
                     :tests [{:ns 't.a.interface-test :file "a/interface_test.clj"
                              :requires [{:ns 't.a.interface :as 'a :line 2}]
                              :references #{'a/f 'is}}]}
                    {:brick {:name "b" :type :component}
                     :metrics {}
                     :sources [{:ns 't.b.interface :file "b/interface.clj"
                                :requires [] :definitions []}]
                     :tests [{:ns 't.b.core-test :file "b/core_test.clj"
                              :requires [{:ns 't.a.interface :refer ['g] :line 2}
                                         {:ns 't.a.core :line 3}]
                              :references #{'g}}]}])]
    (testing "interface definitions no test in the workspace mentions"
      (is (= 1 (get-in (metrics-by-name analysis) ["a" :untested-interface]))
        "f is mentioned in a's tests and g in b's; h in none"))
    (testing "tests that require another brick's implementation"
      (is (= [["b" {:file "b/core_test.clj" :line 3}
               "requires t.a.core, inside a; test through its interface instead"]]
            (->> (dependencies/check {} analysis)
              (filter #(= :test-boundary (:metric %)))
              (map (juxt (comp :name :brick) :location :message))))))))

(deftest merge-candidates-test
  ;; s is used only by a, a component 10 times its size. a is used only by
  ;; c, which is the same size. c is used only by base x.
  (let [bricks (brick-forms
                 (conj acyclic
                   (brick "s" :component (source 't.s.interface 10))))
        bricks (update-in bricks [0 :sources 1 :requires]
                 conj {:ns 't.s.interface :line 4})
        candidates (fn [rules]
                     (filter #(= :merge-candidate (:metric %))
                       (dependencies/check rules
                         (dependencies/analyze workspace bricks))))
        violations (candidates {})]
    (testing "a small component with one component dependent"
      (is (= [["s" "a" 0.1 :warning]]
            (map (juxt (comp :name :brick) :subject :value :level)
              violations)))
      (is (re-find #"is used only by a, and has 10% as many forms"
            (:message (first violations)))))
    (testing ":max-size sets how small"
      (is (empty? (candidates {:merge-candidates {:max-size 0.05}})))
      (is (= #{"s" "a"}
            (set (map (comp :name :brick)
                   (candidates {:merge-candidates {:max-size 1.0}}))))))
    (testing "a component used only by a base is not a candidate"
      (is (not-any? #(= "c" (get-in % [:brick :name])) violations)))))

(defn- defs
  [& specs]
  (vec (for [[name & references] specs]
         {:name name :line 1 :references (set references)})))

(def ^:private cohesive
  ;; a: interface delegates to core, which also uses b's interface twice.
  ;; b: interface delegates to impl, referred from core.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :sources [{:file "a/interface.clj" :ns 't.a.interface :forms 10
               :requires [{:ns 't.a.core :as 'core}]
               :definitions (defs ['f 'core/f] ['g 'core/g])}
              {:file "a/core.clj" :ns 't.a.core :forms 90
               :requires [{:ns 't.b.interface :as 'b}
                          {:ns 'clojure.string :as 'str}]
               :definitions (defs ['f 'helper 'b/used 'str/join]
                              ['helper 'b/used 'inc]
                              ['g 'map])}]}
   {:brick {:name "b" :type :component}
    :metrics {}
    :sources [{:file "b/interface.clj" :ns 't.b.interface :forms 5
               :requires [{:ns 't.b.core :refer ['impl]}]
               :definitions (defs ['used 'impl] ['unused 'impl])}
              {:file "b/core.clj" :ns 't.b.core :forms 20
               :requires []
               :definitions (defs ['impl])}]}])

(deftest cohesion-test
  (let [analysis (dependencies/analyze workspace cohesive)
        m (metrics-by-name analysis)]
    (testing "own references / workspace references; libraries don't count"
      ;; a: core/f, core/g, helper (own) vs b/used twice (other) = 3/5
      (is (= 0.6 (get-in m ["a" :cohesion])))
      ;; b: impl twice (referred), both own
      (is (= 1.0 (get-in m ["b" :cohesion]))))))

(def ^:private connected
  ;; b's interface function wide is used by a; narrow is used too; unused
  ;; is wide but unused. Both bricks share :id; a also has :only-a.
  ;; Both bricks contain fragment 99 (40 forms), and a also contains a
  ;; smaller fragment 98 inside it, which b has too.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :functions []
    :sources [{:file "a/core.clj" :ns 't.a.core :forms 100
               :requires [{:ns 't.b.interface :as 'b}]
               :definitions (defs ['f 'b/wide 'b/narrow])
               :keywords #{:id :only-a}
               :fragments [{:hash 99 :forms 40 :line 10 :end-line 20}
                           {:hash 98 :forms 31 :line 12 :end-line 15}]}]}
   {:brick {:name "b" :type :component}
    :metrics {}
    :functions [{:name "wide" :file "b/interface.clj" :line 3 :params 4}
                {:name "narrow" :file "b/interface.clj" :line 5 :params 1}
                {:name "unused" :file "b/interface.clj" :line 7 :params 5}]
    :sources [{:file "b/interface.clj" :ns 't.b.interface :forms 10
               :requires []
               :definitions (defs ['wide] ['narrow] ['unused])
               :keywords #{:id}
               :fragments [{:hash 99 :forms 40 :line 30 :end-line 40}
                           {:hash 98 :forms 31 :line 32 :end-line 35}]}]}])

(deftest connascence-test
  (let [analysis (dependencies/analyze workspace connected)
        violations (group-by :metric (dependencies/check {} analysis))]
    (testing "meaning: shared keywords"
      (is (= {"a" 1 "b" 1}
            (update-vals (metrics-by-name analysis) :shared-keywords))))
    (testing "position: only interface functions other bricks use"
      (is (= [["b" "wide" 4 {:file "b/interface.clj" :line 3 :name "wide"}]]
            (map (juxt (comp :name :brick) :subject :value :location)
              (:connascence-of-position violations)))))
    (testing "algorithm: the largest duplicated fragment, in each brick"
      (is (= #{["a" 40 10] ["b" 40 30]}
            (set (map (juxt (comp :name :brick) :value (comp :line :location))
                   (:duplicate-code violations)))))
      (is (re-find #"duplicates 40 forms in b \(b/interface.clj:30\)"
            (:message (first (filter #(= "a" (get-in % [:brick :name]))
                               (:duplicate-code violations)))))))
    (testing "settings merge over the defaults"
      (is (empty? (:duplicate-code
                   (group-by :metric
                     (dependencies/check {:duplicate-code {:min-forms 50}}
                       analysis)))))
      (is (= [:warning]
            (distinct (map :level (dependencies/check
                                    {:connascence-of-position {:max 2}}
                                    analysis))))))))

(deftest neighbors-test
  (is (= [{:brick {:name "a"} :depends-on ["b" "c"] :depended-on-by []}
          {:brick {:name "b"} :depends-on [] :depended-on-by ["a"]}]
        (dependencies/neighbors [{:brick {:name "a"}} {:brick {:name "b"}}]
          [{:from "a" :to "c"} {:from "a" :to "b"} {:from "a" :to "b"}]))))

(deftest mermaid-test
  (is (= (str "graph TD\n"
           "  b0([\"cli\"])\n"
           "  b1[\"a\"]\n"
           "  b2[\"b\"]\n"
           "  b0 --> b1\n"
           "  b1 -.-> b2\n"
           "  b2 --> b1\n"
           "  b0 -.- b2\n"
           "  linkStyle 1 stroke:#d1242f,stroke-width:2px\n"
           "  linkStyle 2 stroke:#d1242f,stroke-width:2px\n"
           "  linkStyle 3 stroke:#b26b00,stroke-width:2px,stroke-dasharray:2 4")
        (dependencies/mermaid
          [{:brick {:name "cli" :type :base}}
           {:brick {:name "a" :type :component}}
           {:brick {:name "b" :type :component}}]
          [{:from "cli" :to "a"} {:from "a" :to "b"} {:from "b" :to "a"}
           {:from "cli" :to "a"}]
          [{:metric :stable-dependencies :brick {:name "a"} :subject "b"}
           {:metric :stable-dependencies :brick {:name "b"} :subject "a"}
           {:metric :new-dependency :brick {:name "a"} :subject "b"}
           {:metric :co-change :brick {:name "cli"} :subject "b"}]))))
