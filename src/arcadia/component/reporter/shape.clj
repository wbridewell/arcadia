;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reporter.shape -- component that assigns lexical descriptions of shape (e.g. "rectangle")
;; to objects based on contour data. primarily for demostration purposes currently.
;; is sensitive to object/video size, which may necessitate fiddling around with epsilon values
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ns arcadia.component.reporter.shape
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]
            (arcadia.utility [objects :as obj] [opencv :as cv])))

(def epsilon 0.01)
(def area-epsilon 0.15)

(defn- shape-description [segment]
    (let [contour (-> segment :contour (cv/convert-to cv/CV_32F))
          num-vs
          (-> contour
              (cv/approx-poly-dp (* epsilon (cv/arc-length contour true)) true)
              cv/height)
          min-rect-area (-> contour cv/min-area-rect (#(* (:width %) (:height %))))
          rect-ratio (/ min-rect-area (:area segment))]
      (cond
          (= num-vs 3)
          "triangle"

          (and (< rect-ratio (+ 1.0 area-epsilon)) (> rect-ratio (- 1.0 area-epsilon)))
          "rectangle"

          (= num-vs 4)
          "rectangle"

          (= num-vs 5)
          "pentagon"

          (= num-vs 6)
          "hexagon"

          (= num-vs 7)
          ;"heptagon"
          "rectangle"

          (= num-vs 8)
          "octagon"

          :else
          "circle")))

; original version 
(defn report-shape [segment description source]
    {:name "object-shape"
     :arguments {:segment segment :shape-description description}
     :type "feature"
     :world nil
     :source source})

;; (defn- make-fixation [segment label component]
;;   {:name "fixation" 
;;    :arguments {:segment segment :reason "shape" :shape label}
;;    :world nil
;;    :source component
;;    :type "instance"})

(defrecord ShapeReporter [buffer]
  Component
  (receive-focus
   [component focus content]
   (let [segments (obj/get-segments-persistant content)]
        (if segments
          (reset! (:buffer component) (map #(report-shape % (shape-description %) component) segments))
          ;; (reset! (:buffer component) (map #(make-fixation % (shape-description %) component) segments))
          (reset! (:buffer component) nil))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ShapeReporter [comp ^java.io.Writer w]
  (.write w (format "ShapeReporter{}")))

(defn start []
  (ShapeReporter. (atom nil)))
