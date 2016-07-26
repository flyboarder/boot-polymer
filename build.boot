(set-env!
 :dependencies  '[[org.clojure/clojure  "1.7.0"]
                  [boot/core            "2.6.0"]
                  [adzerk/bootlaces     "0.1.13" :scope "test"]
                  [degree9/boot-semver  "1.2.4" :scope "test"]
                  [degree9/dickory-dock "0.2.0-SNAPSHOT"]
                  [degree9/silicone     "0.6.0-SNAPSHOT"]
                  [hickory              "0.6.0"]
                  [com.helger/ph-css    "5.0.0"]
                  [clojurewerkz/urly    "1.0.0"]]

 :resource-paths   #{"src"})

(require
 '[adzerk.bootlaces :refer :all]
 '[boot-semver.core :refer :all])

(task-options!
  pom {:project 'degree9/boot-polymer
       :version (get-version)
       :description "Boot-clj tasks for working with Polymer"
       :url         "https://github.com/degree9/boot-polymer"
       :scm         {:url "https://github.com/degree9/boot-polymer"}})

(deftask dev
  "Build boot-polymer for development."
  []
  (comp
   (watch)
   (version :no-update true
            :minor 'inc
            :patch 'zero
            :pre-release 'snapshot)
   (target  :dir #{"target"})
   (build-jar)))

(deftask deploy
  "Build boot-polymer and deploy to clojars."
  []
  (comp
   (version :minor 'inc
            :patch 'zero)
   (target  :dir #{"target"})
   (build-jar)
   (push-release)))
