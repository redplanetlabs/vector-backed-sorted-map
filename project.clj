(defproject com.rpl/vector-backed-sorted-map "0.1.0"
  :description "A sorted map implementation designed for ultra-fast initialization and merge operations."
  :url "https://github.com/redplanetlabs/vector-backed-sorted-map"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [potemkin "0.4.5"]]
  :test-paths ["test"]
  :plugins [[lein-exec "0.3.7"]]
  :profiles {:dev {:dependencies
                   [[org.clojure/test.check "0.9.0"]
                    [org.clojure/spec.alpha "0.2.176"]]}
             :check {:warn-on-reflection true}
             :nrepl {:lein-tools-deps/config {:resolve-aliases [:nrepl]}}
             :aot {:aot :all}
             }
  )
