(ns arcadia.component.motion-detector
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.objects :as obj]))

(def ^:parameter motion-threshold
  "the pixel distance differential detectable by this component"
  0.0)

(def ^:parameter delta-count "the number of cycles needed to put out an event" 1)
(def ^:parameter motion-lifespan
  "the number of cycles for a motion event to decay in episodic memory"
  1)

(def ^:parameter debug "print out all motion/stasis events every cycle" false)

(defn get-delta-x [obj]
  (or (:precise-delta-x (:arguments obj))
      (:delta-x (:arguments obj))))

(defn- get-delta-y [obj]
  (or (:precise-delta-y (:arguments obj))
      (:delta-y (:arguments obj))))

(defn- above-threshold [x threshold]
  (and x (> (Math/abs (float x)) threshold)))


(defn- is-moving?
  "For tracked objects in VSTM, the object is moving if
   the object-location from this cycle isn't the same as when
   the object was last attended to"
  [new params]
  (or (above-threshold (get-delta-x new) (:motion-threshold params))
      (above-threshold (get-delta-y new) (:motion-threshold params))))

(defn- dir-val [dv motion-threshold]
  (if (above-threshold dv motion-threshold) (compare dv 0) 0))

(defn- get-direction
  "Obtains the direction for the motion event. Cardinal directions
   can be obtained without stored trajectory information,
   but :dir requires information from highlighter.maintenance."
  [new params]
  (let [dx (get-delta-x new)
        dy (get-delta-y new)]
    {:x-dir (when dx (dir-val dx (:motion-threshold params)))
     :y-dir (when dy (dir-val dy (:motion-threshold params)))
     :dir (when (and dx dy) (Math/atan2 dy dx))}))

(defn- motion-event [obj dir component]
  {:name "event"
   :arguments (assoc dir
                     :event-name "motion"
                     :objects (list obj)
                     :event-lifespan (:motion-lifespan (:params component)))
   :type "instance"
   :source component
   :world nil})

(defn- stasis-event [obj component]
  {:name "event"
   :arguments {:event-name "stasis"
               :objects (list obj)
               :event-lifespan (:motion-lifespan (:params component))}
   :type "instance"
   :source component
   :world nil})

(defrecord MotionDetector [buffer params] Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component)
           (mapcat #(cond
                      (is-moving? % (:params component))
                      (list (motion-event % (get-direction % (:params component)) component))

                      (some-> % :arguments :precise-delta-count (>= (:delta-count (:params component))))
                      (list (stasis-event % component)))
                   (obj/get-vstm-objects content :tracked? true)))
   (when (:debug (:params component))
    (doall (map #(if (= (:event-name (:arguments %)) "stasis")
                   (println :MOTION "-----STATIC: "
                            (-> % :arguments :objects first :arguments
                                ((juxt :color :precise-delta-x :precise-delta-y :precise-delta-count))))
                   (println :MOTION "-----MOTION: "
                            (-> % :arguments :objects first :arguments
                                ((juxt :color :precise-delta-x :precise-delta-y :precise-delta-count)))
                            (-> % :arguments ((juxt :x-dir :y-dir)))
                            (-> % :arguments :objects first :arguments :direction-change?)))
                @(:buffer component)))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method MotionDetector [comp ^java.io.Writer w]
  (.write w (format "MotionDetector{}")))

(defn start [& {:as params}]
  (->MotionDetector (atom nil) (merge-parameters params)))
