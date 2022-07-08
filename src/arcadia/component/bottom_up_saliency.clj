(ns arcadia.component.bottom-up-saliency
  "A saliency map integrates information about contrast across features and
  scales. The most salient region is taken to be the place where someone would
  look if they had no particular goals driving their behavior.

  This component computes a stimulus-driven saliency map for
  the field of view reported by the registered image sensor.

  Focus Responsive
  No

  Default Behavior
    Read the current image from the associated sensor.
    Construct a saliency map that takes into account a specified set of features
    that can include
      :color
      :intensity
      :orientations
      :flicker
      :motions
    Also calculate the maximum point in the saliency map.

    Features :flicker and :motions compare the previous image to the current
    image to identify contrast over time.

  Produces
   * saliency-map
       includes
          :image-mat, which is an OpenCV matrix representation of the saliency map,
          :max-point, which is the most salient point in the image, or one of these
                      if multiple points are equally salient, and
          :sensor, which is the sensor that this component uses."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :refer [poll]]
            [arcadia.utility [opencv :as cv] [saliency :as sal]]))


(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(defn- make-maps [component]
  {:name "saliency-map"
   :arguments {:image-mat (:saliency @(:buffer component))
               :max-point @(:max-point component)
               :sensor (:sensor component)}
   :world nil
   :source component
   :type "instance"})

;; Operates solely over sensor input, hence "bottom-up".
(defrecord BottomUpSaliency [sensor parameters buffer history max-point]
  Component
  (receive-focus
   [component focus content]
   (let [data (poll (:sensor component))]
     (when (:image data)
       (let [image (cv/convert-to (:image data) cv/CV_32F)
             smap (sal/saliency image {}
                                :previous-img @(:history component))
             maxloc (when (:saliency smap)
                      (:max-loc (cv/min-max-loc (:saliency smap))))]
         (dosync
          (if maxloc
            (do
              (reset! (:max-point component) (java.awt.Point. (+ (:xcorner (:location data)) (:x maxloc))
                                                              (+ (:ycorner (:location data)) (:y maxloc))))
              (reset! (:buffer component) smap))
            (do
              (reset! (:max-point component) nil)
              (reset! (:buffer component) nil)))
          (reset! (:history component) image))))))

  (deliver-result
   [component]
   (when @(:buffer component)
     #{(make-maps component)})))

(defmethod print-method BottomUpSaliency [comp ^java.io.Writer w]
  (.write w (format "BottomUpSaliency{}")))

(defn start
  "Instantiate a sensor with a set of features including :color, :intensity,
  :orientations, :flicker, and :motions. Features are specified as a set
  with the keyword :features. If no features are specified, use those found in
  in the default parameters."
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->BottomUpSaliency (:sensor p)
                        p
                        (atom nil) (atom nil) (atom nil))))
