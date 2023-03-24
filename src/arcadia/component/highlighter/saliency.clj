(ns arcadia.component.highlighter.saliency
  "This component requests a fixation to an object at one of the four most
  salient regions in the sensor's field of view if such an object exists.
  In addition, up to three other highly salient regions are produced.

  Focus Responsive
  No

  Default Behavior
  Look for the proto-object that appears in one of the four most salient
  locations in the scene and produce a corresponding fixation request. The
  \"salient\" fixation is selected by a weighted choice function. Also produce
  fixation requests for up to 3 other highly salient proto-objects.

  Produces
   * fixation
       includes a :segment that contains the segment driving fixation,
       a :sensor that contains the sensor associated with the visual field, and
       a :reason that specifies this as resulting from being the point of
       preferred \"saliency\" or one of the other salient segments in the \"scene\".

  NOTE: See Xu, Y., & Chun, M. M. (2009). Selecting and perceiving multiple
  visual objects. Trends in Cognitive Science,  13, 167–174.
  which suggests that up to 4 salient objects are presented for potential
  visual fixation."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [general :as g] [opencv :as cv]]
            [clojure.data.generators :as dgen]))

(def ^:parameter ^:required map-sensor
  "a sensor used to generate a saliency map from visual input (required)"
  nil)

(def ^:parameter ^:required segment-sensor
  "a sensor used to generate proto-object representations from visual input (required)"
  nil)

;; limit to the four most salient regions a la Xu and Chun's suggestion.
(def ^:private set-size 4)

(defn- make-fixation [segment reason component]
  (when segment
    {:name "fixation"
     :arguments {:segment segment
                 :sensor (:map-sensor component)
                 :reason reason
                 :most-salient? (= reason "saliency")}
     :world nil
     :type "instance"}))

(defn- segment-saliency
  "Calulate the maximum saliency associated with the segment."
  [smatrix segment]
  (-> smatrix (cv/submat (:region segment)) cv/min-max-loc :max-val))

(defn- weighted-choice
  "Randomly select one of the items in a set of [weight, item] tuples using the
  weight to modify the probability of selection. Return a tuple containing the
  selected item and a list of the others."
  [tuples]
  ;; tuple = [weight, item]
  ;; tuples are sorted by weight in > order
  ;; return [selected-item rest]
  (loop [mass (* (dgen/double) (apply + (map first tuples)))
         elements tuples
         answer nil]
    (if (or (neg? mass) (empty? elements))
      [(second answer) (map second (remove #{answer} tuples))]
      (recur (- mass (first (first elements)))
             (rest elements)
             (first elements)))))

(defrecord SaliencyHighlighter [map-sensor segment-sensor buffer]
  Component
  (receive-focus
    [component focus content]
    (let [saliency-map (g/find-first #(and (= (:name %) "saliency-map")
                                           (= (-> % :arguments :sensor)
                                              (:map-sensor component)))
                                     content)
          segments (g/find-first #(and (= (:name %) "image-segmentation")
                                       (= (-> % :arguments :sensor)
                                          (:segment-sensor component)))
                                 content)]
      (if (and saliency-map segments)
        (reset! (:buffer component)
                (weighted-choice
                 (take set-size
                       (sort-by first >
                                (map (fn [x] [(segment-saliency (-> saliency-map :arguments :image-mat) x) x])
                                     (-> segments :arguments :segments))))))
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (when (seq (remove #(or (nil? %) (empty? %)) @(:buffer component)))
      (into ()
            (conj (map #(make-fixation % "scene" component) (second @(:buffer component)))
                  (make-fixation (first @(:buffer component)) "saliency" component))))))

(defmethod print-method SaliencyHighlighter [comp ^java.io.Writer w]
  (.write w (format "SaliencyHighlighter{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->SaliencyHighlighter (:map-sensor p) (:segment-sensor p) (atom nil))))
