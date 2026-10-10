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

(defn- found
  "The findings of metric k in analysis, with settings."
  ([k analysis] (found k {} analysis))
  ([k settings analysis]
   (get (dependencies/findings settings analysis) k)))

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
              bases have none"
      (is (= 0.9 (get-in m ["a" :abstractness])))
      (is (= 0.5 (get-in m ["b" :abstractness])))
      (is (nil? (get-in m ["x" :abstractness]))))
    (testing "main-sequence distance: |abstractness + instability - 1|"
      (is (< 0.399 (get-in m ["a" :main-sequence-distance]) 0.401))
      (is (= 0.5 (get-in m ["b" :main-sequence-distance])))
      (is (nil? (get-in m ["x" :main-sequence-distance]))))
    (testing "no unstable dependencies"
      (is (empty? (found :unstable-dependencies analysis))))))

(deftest isolated-brick-test
  (let [m (metrics-by-name
            (dependencies/analyze workspace
              [(brick "lonely" :component (source 't.lonely.interface 10))]))]
    (is (= {:afferent 0 :efferent 0 :instability nil :abstractness 0.0
            :cohesion nil :workspace-references 0 :shared-keywords 0
            :libraries 0 :error-surface 0.0 :throwing-interface 0
            :interface-definitions 1 :untested-interface 1.0
            :untested-definitions 1 :main-sequence-distance nil}
          (m "lonely")))))

(deftest unstable-dependencies-test
  (let [;; b now requires c, so b (stable) depends on c (less stable)
        bricks (assoc-in acyclic [1 :sources 1 :requires]
                 [{:ns 't.c.interface :line 9}])
        [finding & more] (found :unstable-dependencies
                           (dependencies/analyze workspace bricks))]
    (is (empty? more))
    (is (= ["b" "c" {:file "t.b.core.clj" :line 9}]
          ((juxt (comp :name :brick) :subject :location) finding)))
    (is (< 0.166 (:value finding) 0.167)
      "the value is how much less stable the dependency is")
    (is (re-find #"depends on c \(instability 0\.5\), which is less stable than b \(0\.33\)"
          (:message finding)))))

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
      (is (= {"a" 1 "b" 2 "x" 2} (update-vals m :libraries))))
    (testing "a finding at each brick's first require of a shared library"
      (is (= [["a" "next.jdbc" 2 {:file "a/core.clj" :line 3}
               "requires next.jdbc, which b also requires"]
              ["b" "next.jdbc" 2 {:file "b/core.clj" :line 3}
               "requires next.jdbc, which a also requires"]]
            (map (juxt (comp :name :brick) :subject :value :location :message)
              (found :library-spread analysis)))))
    (testing "allowed libraries are left out"
      (is (empty? (found :library-spread
                    {:library-spread {:allow #{"next.jdbc"}}} analysis)))
      (is (empty? (found :library-spread
                    {:library-spread {:allow #{'next.jdbc}}} analysis))))))

(deftest mutable-state-test
  (let [state [{:name 'cache :line 2 :kind :atom}]
        findings (found :mutable-state
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
                                  :mutable-state state}]}]))]
    (is (= [["a" {:file "a/core.clj" :line 2 :name "cache"}
             "defines an atom, state hidden from the functions that use it"]
            ["a" {:file "a/interface.clj" :line 2 :name "cache"}
             (str "defines an atom in its interface, so every brick that"
               " uses it shares the state")]
            ["x" {:file "x/main.clj" :line 2 :name "cache"}
             "defines an atom, state hidden from the functions that use it"]]
          (map (juxt (comp :name :brick) :location :message) findings))
      "every brick: the metric decides which it checks")))

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
    (is (= {"a" 0.5 "b" 1.0 "x" nil} (update-vals m :error-surface))
      "the share that throws, across bricks, through referred and aliased
      symbols; bases have none")
    (is (= {"a" [1 2] "b" [1 1] "x" [nil nil]}
          (update-vals m (juxt :throwing-interface :interface-definitions))))))

(deftest broad-catches-test
  (is (= [["a" {:file "a/core.clj" :line 4}
           "catches Exception, deciding for every caller what a failure means"]
          ["x" {:file "x/main.clj" :line 2}
           "catches Throwable, deciding for every caller what a failure means"]]
        (->> (dependencies/analyze workspace
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
                                       :broad? true}]}]}])
          (found :broad-catches)
          (map (juxt (comp :name :brick) :location :message))))))

(deftest untyped-errors-test
  (is (= [["a" {:file "a/core.clj" :line 2}
           "throws ex-info without a :type in its data"]
          ["a" {:file "a/core.clj" :line 3}
           "throws a host exception, which callers can tell apart only by class"]]
        (->> (dependencies/analyze workspace
               [{:brick {:name "a" :type :component}
                 :metrics {}
                 :sources [{:file "a/core.clj" :ns 't.a.core
                            :throws [{:line 1 :kind :typed}
                                     {:line 2 :kind :untyped}
                                     {:line 3 :kind :host}
                                     {:line 4 :kind :rethrow}
                                     {:line 5 :kind :unknown}]}]}])
          (found :untyped-errors)
          (map (juxt (comp :name :brick) :location :message))))))

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
      (is (= [(/ 1 3.0) 1]
            ((juxt :untested-interface :untested-definitions)
             (get (metrics-by-name analysis) "a")))
        "f is mentioned in a's tests and g in b's; h in none"))
    (testing "tests that require another brick's implementation"
      (is (= [["b" {:file "b/core_test.clj" :line 3}
               "requires t.a.core, inside a; test through its interface instead"]]
            (map (juxt (comp :name :brick) :location :message)
              (found :boundary-crossings analysis)))))))

(deftest merge-candidates-test
  ;; s is used only by a, a component 10 times its size. a is used only by
  ;; c, which is the same size. c is used only by base x.
  (let [bricks (brick-forms
                 (conj acyclic
                   (brick "s" :component (source 't.s.interface 10))))
        bricks (update-in bricks [0 :sources 1 :requires]
                 conj {:ns 't.s.interface :line 4})
        candidates (found :merge-candidate
                     (dependencies/analyze workspace bricks))]
    (testing "components with one component dependent, by relative size"
      (is (= #{["s" "a" 0.1] ["a" "c" 1.0]}
            (set (map (juxt (comp :name :brick) :subject :value) candidates))))
      (is (re-find #"is used only by a, and has 10% as many forms"
            (:message (first (filter #(= "s" (get-in % [:brick :name]))
                               candidates))))))
    (testing "a component used only by a base is not a candidate"
      (is (not-any? #(= "c" (get-in % [:brick :name])) candidates)))))

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
  ;; is wide but unused. Both bricks share :id and :x/shared; a also has
  ;; :only-a.
  ;; Both bricks contain fragment 99 (40 forms), and a also contains a
  ;; smaller fragment 98 inside it, which b has too.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :functions []
    :sources [{:file "a/core.clj" :ns 't.a.core :forms 100
               :requires [{:ns 't.b.interface :as 'b}]
               :definitions (defs ['f 'b/wide 'b/narrow])
               :keywords #{:id :only-a :x/shared}
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
               :keywords #{:id :x/shared}
               :fragments [{:hash 99 :forms 40 :line 30 :end-line 40}
                           {:hash 98 :forms 31 :line 32 :end-line 35}]}]}])

(deftest connascence-test
  (let [analysis (dependencies/analyze workspace connected)
        findings (dependencies/findings {:duplicate-code {:warning 30}}
                   analysis)]
    (testing "meaning: shared qualified keywords"
      (is (= {"a" 1 "b" 1}
            (update-vals (metrics-by-name analysis) :shared-keywords))))
    (testing "position: only interface functions other bricks use"
      (is (= [["b" "wide" 4 {:file "b/interface.clj" :line 3 :name "wide"}]
              ["b" "narrow" 1 {:file "b/interface.clj" :line 5 :name "narrow"}]]
            (map (juxt (comp :name :brick) :subject :value :location)
              (:positional-interface findings)))))
    (testing "algorithm: the largest duplicated fragment, in each brick"
      (is (= #{["a" 40 10] ["b" 40 30]}
            (set (map (juxt (comp :name :brick) :value (comp :line :location))
                   (:duplicate-code findings)))))
      (is (re-find #"duplicates 40 forms in b \(b/interface.clj:30\)"
            (:message (first (filter #(= "a" (get-in % [:brick :name]))
                               (:duplicate-code findings)))))))
    (testing "duplicates no bigger than the lowest limit aren't collected"
      (is (empty? (found :duplicate-code {:duplicate-code {:warning 40}}
                    analysis)))
      (is (= 2 (count (found :duplicate-code
                        {:duplicate-code {:warning 50 :error 39}}
                        analysis)))))
    (testing "off without thresholds"
      (is (nil? (found :duplicate-code {:duplicate-code {:warning nil}}
                  analysis))))))

(deftest public-interface-test
  (let [m (metrics-by-name
            (dependencies/analyze workspace
              [(brick "a" :component
                 (assoc (source 't.a.interface 20)
                   :definitions [{:name 'f :line 1 :references #{}}
                                 {:name 'helper :line 2 :references #{}
                                  :private? true}])
                 (source 't.a.core 20))]))]
    (is (= 0.75 (get-in m ["a" :abstractness]))
      "a private definition in the interface isn't interface")
    (is (= 1 (get-in m ["a" :interface-definitions])))))

(deftest cohesion-types-test
  (let [m (metrics-by-name (dependencies/analyze workspace cohesive))]
    (is (= 5 (get-in m ["a" :workspace-references])))
    (is (nil? (get-in (metrics-by-name
                        (dependencies/analyze workspace
                          [(brick "x" :base (source 't.x.main 10))]))
                ["x" :cohesion]))
      "bases have no cohesion")))

(deftest library-names-test
  (is (= ["java-time" "systems.thoughtfull.amalgam"
          "systems.thoughtfull.desiderata"]
        (map :library
          (:libraries
           (dependencies/analyze workspace
             [{:brick {:name "a" :type :component}
               :metrics {}
               :sources [{:file "a.clj" :ns 't.a.core
                          :requires [{:ns 'systems.thoughtfull.amalgam :line 1}
                                     {:ns 'systems.thoughtfull.desiderata
                                      :line 2}
                                     {:ns 'java-time.api :line 3}
                                     {:ns 'java-time.clock :line 4}]}]}]))))
    "a reverse-domain name keeps its organization and library; others
    group by their first segment"))

(deftest clojurescript-library-names-test
  (is (= ["@mui/material" "cljs.core.async" "react" "react-dom"]
        (map :library
          (:libraries
           (dependencies/analyze workspace
             [{:brick {:name "a" :type :component}
               :metrics {}
               :sources [{:file "a.cljs" :ns 't.a.core
                          :requires [{:ns "react" :line 1}
                                     {:ns "react-dom/client" :line 2}
                                     {:ns "@mui/material/Button" :line 3}
                                     {:ns 'cljs.core.async :line 4}
                                     {:ns 'cljs.test :line 5}
                                     {:ns 'goog.string :line 6}
                                     {:ns 'goog :line 7}]}]}]))))
    (str "JavaScript modules are named by their npm package; cljs.test and"
      " the Closure Library are part of the platform")))

(deftest test-support-test
  (is (empty? (found :boundary-crossings
                (dependencies/analyze workspace
                  [{:brick {:name "a" :type :component}
                    :metrics {}
                    :sources [{:ns 't.a.interface :file "a/interface.clj"
                               :requires [] :definitions []}]
                    :tests [{:ns 't.a.generators :file "a/generators.clj"
                             :requires []}]}
                   {:brick {:name "b" :type :component}
                    :metrics {}
                    :sources []
                    :tests [{:ns 't.b.core-test :file "b/core_test.clj"
                             :requires [{:ns 't.a.generators :line 2}]}]}])))
    "another brick's test namespaces, such as generators, aren't crossings"))

(deftest catch-handling-test
  (is (= ["catches Exception and logs it, deciding for every caller what a failure means"
          "catches Throwable and carries on, deciding for every caller what a failure means"]
        (->> (dependencies/analyze workspace
               [{:brick {:name "a" :type :component}
                 :metrics {}
                 :sources [{:file "a/core.clj" :ns 't.a.core
                            :catches [{:line 1 :class "Exception" :broad? true
                                       :handling :rethrows}
                                      {:line 2 :class "Exception" :broad? true
                                       :handling :logs}
                                      {:line 3 :class "Throwable" :broad? true
                                       :handling :continues}]}]}])
          (found :broad-catches)
          (map :message)))
    "a catch that rethrows translates the failure, so it doesn't count"))

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
          [{:from "cli" :to "a"} {:from "a" :to "b" :new? true}
           {:from "b" :to "a"} {:from "cli" :to "a"}]
          [{:metric :unstable-dependencies :brick {:name "a"} :subject "b"}
           {:metric :unstable-dependencies :brick {:name "b"} :subject "a"}
           {:metric :co-change :brick {:name "cli"} :subject "b"}]))))
