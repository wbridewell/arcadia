(load "arcadia/core")


(def files-path (.getPath (clojure.java.io/resource "mot-videos/files.tab")))

(load-file (str files-path))

(count mot-files)

;; path
(str "mot-videos/" (first (first mot-files)))
;; frame count
(second (first mot-files))

(def cycles 42);9)
;(arcadia.core/startup cycles)

(list (first mot-files))


(defn report-parameters [filename]
  (clojure.string/split
    (second (clojure.string/split
              (first (clojure.string/split filename #"\."))
              #"/"))
    #"-"))

(defn location-match?
  ([r1 r2 epsilon]
   (and
     r1
     r2
     (.intersects r1 r2)
     (arcadia.utility.objects/similar-size? r1 r2 epsilon)))

  ([r1 r2]
   (location-match? r1 r2 0.25)))


;; returns a segment
(defn match-region [region segments]
  (first (filter #(location-match? region (:region %)) segments)))

(defn keeper? [r1 rects]
  (or (empty? rects)
      (not-any? #(location-match? r1 %) rects)))

;; terribly sloppy way to get the job done.
(defn get-unique-vstm-regions [content]
  (let [regions (doall
                  (map #(:location (:arguments %))
                       (filter #(and (= (:name %) "object-location")
                                     (= (:type %) "relation")
                                     (= (:world %) "vstm")) content)))]
    (loop [r (first regions)
           rs (rest regions)
           collector ()]
      (if r
        (recur (first rs) (rest rs) (if (keeper? r rs) (conj collector r) collector))
        collector))))


;    (filter #(keeper? % (remove #{%} regions)) regions)))

(defn get-segments [content]
  (:segments (:arguments (first (filter #(= (:name %) "image-segmentation")
                                        content)))))

(defn count-blue [segments]
  (count (filter arcadia.component.blue-foveator/is-blue? segments)))

(defn gather-results [content]
  (let [segments (get-segments content)
        finst-regions (get-unique-vstm-regions content)]
    (count-blue (map #(match-region % segments) finst-regions))))

(clojure.string/join "," (conj (report-parameters (first (first mot-files))) (str 0 )))


(defn run-trial [file cycles]
  (let [video-path (.getPath (clojure.java.io/resource (str "mot-videos/" file)))]
    (arcadia.core/startup video-path cycles)
    (conj (report-parameters file) (str (gather-results (arcadia.architecture.access/get-content))))))


(use 'clojure.java.io)
(defn do-mot-trials []
  (with-open [wrtr (writer "results.txt")]
    (dotimes [i (count mot-files)]
      (let [[data cycles] (nth mot-files i)
            result (clojure.string/join "," (run-trial data cycles))]
        (println "TRIAL" i ": " result)
        (.write wrtr result)
        (.newLine wrtr)))))

;(run-trial "one/fast-one-midpad-1.mp4" 325)

;  (in-ns 'arcadia.core)
;(def video-path (.getPath (clojure.java.io/resource (str "mot-videos/" (first (first user/mot-files))))))
;  (in-ns 'user)
;arcadia.core/video-path

;(run-trial (first (first mot-files)) 14)

;(arcadia.architecture.access/get-content)

