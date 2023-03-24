(ns arcadia.component.stroop-segmenter
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :refer [poll]]
            [arcadia.utility.opencv :as cv]))

;;
;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;
;;

;; this component creates a single segment out of all the saturated pixels
;; useful for stroop, mostly, where all the stimuli are single words in some
;; color.

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(def ^:parameter min-saturation "the minimal saturation value that will add a pixel to the segment (0 to 255)"
  50)

;;min-saturation could be 50 or something, it's out of 255
(defn- get-saturated-segment
  [img min-saturation]
  (let [full-mask (-> img (cv/cvt-color cv/COLOR_BGR2HSV)
                      (cv/in-range [0 min-saturation 0] [255 255 255]))
        region (cv/non-zero-bounds full-mask)
        mask (cv/submat full-mask region)]
    {:image (cv/copy (cv/submat img region) :mask mask)
     :mask mask
     :area (cv/count-non-zero mask)
     :region region}))

(defn- get-segments
  "Make blame-concept interlingua element for intentionality information
   about the norm violation."
  [img content component]
  (let [segments [(get-saturated-segment img (-> component :parameters :min-saturation))]]
    (when (< 0 (-> segments first :area))
      {:id (gensym)
       :name "text-segmentation"
       :arguments {:segments segments
                   :image img
                   :sensor (:sensor component)}
       :type "instance"
       :world nil})))

(defrecord StroopSegmenter [sensor buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (when-let [img (-> (poll (:sensor component)) :image)]
      (reset! (:buffer component) (get-segments img content component))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method StroopSegmenter [comp ^java.io.Writer w]
  (.write w (format "StroopSegmenter{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->StroopSegmenter (:sensor p) (atom nil) p)))