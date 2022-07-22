(defproject arcadia "0.3.0"
  :description "A framework for building attention-centric cognitive systems."
  :url "https://www.nrl.navy.mil"
  :license {:name "see LICENSE file"}
  :jvm-opts ["-Djava.library.path=/usr/lib:/usr/local/lib:/usr/local/opt/opencv/share/OpenCV/java/" 
             "-Djna.library.path=/usr/local/lib"]
  :plugins [[lein-codox "0.10.8"]
            [lein-marginalia "0.9.1"]
            [lein-count "1.0.9"]]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.generators "1.0.0"]
                 [net.sourceforge.tess4j/tess4j "5.2.1"]
                 [aleph "0.4.6"]
                 [byte-streams "0.2.4"]
                 [gloss "0.2.6"]
                 [manifold "0.1.8"]
                 [local/opencv "arcadia"]
                 [local/opencv-native "arcadia"]
                 [w33t/kawa "0.1.2"]
                 [org.clojure/math.numeric-tower "0.0.5"]
                 [org.clojure/math.combinatorics "0.1.6"]
                 [kephale/uncommons-maths "1.2.3"]
                 [org.clojure/tools.trace "0.7.11"]
                 [org.clojure/tools.namespace "1.3.0"]
                 [incanter "1.9.3"]
                 [net.mikera/core.matrix "0.62.0"]
                 [net.mikera/vectorz-clj "0.48.0"]
                 [codox-theme-rdash "0.1.2"]
                 [cnuernber/dtype-next "9.028" :exclusions [ch.qos.logback/logback-classic org.clojure/tools.logging]]] 
  :codox {:project {:name "ARCADIA"
                    :themes [:rdash]
                    :source-paths ["src"]}}

  :injections [(clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)
               (require '[clojure.tools.namespace.repl :refer [refresh refresh-all]])]
  :repositories {"project" {:url "file:repo" :update :always}
                 "jcenter" {:id "central" :url "https://jcenter.bintray.com"}}
  :profiles {:python {:dependencies [[clj-python/libpython-clj "2.018"]
                                     [nrepl/nrepl "0.9.0"]
                                     [org.nrepl/incomplete "0.1.0"]]
                      :jvm-opts ["--add-modules" "jdk.incubator.foreign"
                                 "--enable-native-access=ALL-UNNAMED"]}
             :aarch64 {:dependencies [[nrepl/nrepl "0.9.0"]
                                      [org.nrepl/incomplete "0.1.0"]]
                       :jvm-opts ["-Djava.library.path=/usr/lib/jni:/opt/homebrew/lib:/opt/homebrew/opt/opencv/share/java/opencv4"
                                  "-Djna.library.path=/opt/homebrew/lib" 
                                  "-Dapple.awt.UIElement=true"]}})
                                     
