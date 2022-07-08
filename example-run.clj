(load "arcadia/core")

(def video-path (.getPath (clojure.java.io/resource "mot-videos/one/fast-one-largepad-1.mp4"))); "inattentional-blindness.m4v")))
(def image-path (.getPath (clojure.java.io/resource "beach-boys.jpg")))

#_(arcadia.core/startup 'arcadia.models.mot-blue
                      'arcadia.simulator.environment.static-image
                       {:image-path image-path}  5)

(arcadia.core/startup 'arcadia.models.iab-count
                      'arcadia.simulator.environment.video-player
                      {:video-path video-path} 5)


