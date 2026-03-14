(in-ns 'user)

((requiring-resolve 'clojure+.error/install!))
((requiring-resolve 'clojure+.hashp/install!))
((requiring-resolve 'clojure+.print/install!))
((requiring-resolve 'clojure+.test/install!))

(require '[clj-reload.core :as reload])
(reload/init {:dirs ["src/main/clj" "src/test/clj"]})
