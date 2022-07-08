(ns arcadia.component.highlighter.maintenance
  "After we have fixated on an object and built an object file,
  this component asks to continue fixating on the object for as
  long as possible.  Over this time, it updates motion information
  for the object.

  Focus Responsive
    * fixation
    * object

  Keeps information about an object's motion up to date, provided the
  focus remains either on the object or on a maintenance fixation to the
  object (typically, the focus alternates between these).

  Default Behavior
  Calculates an attended object's location, its velocity, and its
  next expected location. Velocity is represented as :delta-x/:delta-y,
  and as :precise-delta-x/:precise-delta-y, which are averaged over
  several cycles.

  Produces
    * fixation (reason: \"maintenance\")
       A request to maintain attention on the currenlty attended object.
       Inludes several pieces of information:
       :object             The attended object
       :delta-x/:delta-y   The object's change per cycles in x/y values
       :precise-delta-x/:precise-delta-y More precise velocity values
                                         averaged over several cycles
       :precise-x-dir/:precise-y-dir     -1/0/1 directions of motion
       :precise-delta-count The number of cycles over which precise
                            values were computed.

   * fixation (reason: \"collision\")
      A seperate request to fixate on the same object, only produced
      when the object is undergoing a collision with another object."
  (:require [clojure.math.numeric-tower :as math]
            [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [descriptors :as d] [objects :as obj]]
            [arcadia.vision.regions :as reg]))

(def ^:parameter min-delta
  "minimum change in position that will count as perceptable motion"
  0.00001)

(def ^:parameter max-delta-queue-count "max # of velocities to average over" 16)
(def ^:parameter min-delta-queue-count "min # of velocities to average over" 6)
(def ^:parameter post-collision-delay
  "delay this many cycles after a collision before updating trajectory info"
  2)

(defn- dc-stem
  "Stub for a function that should return true when a collision has occurred."
  [obj1 obj2 trajectory-data]
  false)

(defn- dce-stem
  "Stub for a function that should return true at the end of a collision."
  [obj1 obj2 trajectory-data]
  false)

(def ^:parameter detect-collision
  "function that should return true to indicate that a collision has occurred"
  dc-stem)

(def ^:parameter detect-collision-end
  "function that should return true to indicate that a collision has ended"
  dce-stem)

(defn- sequence-mean
  "Calculates the mean of the values in a sequence."
 [sequence]
 (let [sum-count (reduce (fn [[sum n] x] [(+ sum x) (inc n)])
                         [0 0]
                         sequence)]
   (/ (sum-count 0) (sum-count 1))))

(defn- new-xy
  "Returns a map with the new :x and :y values of
  the attended object."
  [tdata old-tdata params]
  (let [region (:region tdata)]
    (cond
      (and region (not (:collision? tdata)))
      (reg/center region)

      region
      {:x (:expected-x old-tdata)
       :y (:expected-y old-tdata)}

      :else
      {:x 0 :y 0})))

(defn- update-post-collision-counter
  "Returns a map with an updated post-collision counter and info about whether
  we are currently in a post-collision holding pattern."
  [tdata old-tdata params]
  (let [old-counter (:post-collision-counter old-tdata)]
    (cond
      (< (:post-collision-delay params) 1)
      {}
      (and (:collision? old-tdata) (not (:collision? tdata)))
      {:post-collision? true :post-collision-counter 1}
      (and old-counter (< old-counter (:post-collision-delay params)))
      {:post-collision? true :post-collision-counter (+ old-counter 1)}
      :else
      {})))


;;;;;
;; Calculate the new delta-x and delta-y values.
;; Average the latest info with any older deltas.
;; If there's a collision, don't trust the latest delta.
(defn- new-deltas
  "Returns a map with the new {:delta-x :delta-y} values, averaging
  the latest information with older deltas. Ignore the
  newest delta during a collision."
  [tdata old-tdata params]
  (let [x (:x tdata)
        y (:y tdata)
        recent-collision? (or (:collision? tdata) (:post-collision? tdata)
                              (:collision? old-tdata))
        old-x (:x old-tdata)
        old-y (:y old-tdata)
        old-delta-x (:delta-x old-tdata)
        old-delta-y (:delta-y old-tdata)]
    (cond
      recent-collision?
      {:delta-x old-delta-x :delta-y old-delta-y}

;;       (and old-x old-delta-x)
;;       {:delta-x (/ (+ (- x old-x) old-delta-x) 2)
;;        :delta-y (/ (+ (- y old-y) old-delta-y) 2)}

      old-x
      {:delta-x (- x old-x) :delta-y (- y old-y)}

      :else
      {})))

;; NOTE: This function should reset the queues any time there are obvious
;; changes in direction of motion, but it doesn't, yet.
(defn- update-delta-queues
  "Returns a map with the updatad queues of position changes over time and their averages."
  [tdata old-tdata params]
  (let [delta-x (:delta-x tdata)
        delta-y (:delta-y tdata)
        delta-x-queue (or (:delta-x-queue old-tdata) '())
        delta-y-queue (or (:delta-y-queue old-tdata) '())
        precise-delta-x (:precise-delta-x old-tdata)
        recent-collision? (or (:collision? tdata) (:post-collision? tdata)
                              (:collision? old-tdata))]
    (cond
      (nil? delta-x)
      {}

      recent-collision?
      {:delta-x-queue delta-x-queue :delta-y-queue delta-y-queue
       :precise-delta-x (:precise-delta-x old-tdata)
       :precise-delta-y (:precise-delta-y old-tdata)}

      :else
      (let [new-x-queue (take (:max-delta-queue-count params) (conj delta-x-queue delta-x))
            new-y-queue (take (:max-delta-queue-count params) (conj delta-y-queue delta-y))]
        {:precise-delta-x (sequence-mean new-x-queue)
         :precise-delta-y (sequence-mean new-y-queue)
         :delta-x-queue new-x-queue
         :delta-y-queue new-y-queue}))))

;;     (if (or (nil? delta-x) (and (zero? delta-x) (zero? delta-y)) recent-collision?)
;;       {}
;;       (let [new-x-queue (take max-delta-queue-count (conj delta-x-queue delta-x))
;;             new-y-queue (take max-delta-queue-count (conj delta-y-queue delta-y))]
;;         {:precise-delta-x (sequence-mean new-x-queue)
;;          :precise-delta-y (sequence-mean new-y-queue)
;;          :delta-x-queue new-x-queue
;;          :delta-y-queue new-y-queue}))))

(defn- detect-direction-change
  "When the direction queues are full, this function calculates how much the
  direction encoded in the two queues has recently changed by looking at the
  difference between the values in the first and second halves of the queues.
  Returns a map in which the Boolean :direction-change? is set based on whether
  the difference exceeds a threshold."
  [tdata old-tdata params]
  (let [delta-x-queue (:delta-x-queue tdata)
        delta-y-queue (:delta-y-queue tdata)]
    (when (= (count delta-x-queue) (:max-delta-queue-count params))
      (let [half-size (quot (:max-delta-queue-count params) 2)
            x-queues (partition half-size delta-x-queue)
            y-queues (partition half-size delta-y-queue)]
        {:direction-change?
         (obj/directions-are-different?
           (obj/direction-difference (sequence-mean (first x-queues))
                                     (sequence-mean (first y-queues))
                                     (sequence-mean (second x-queues))
                                     (sequence-mean (second y-queues))))}))))

(defn- predict-location
  "Predicts the next x and y values with the given deltas. Optionally, the
  position can be predicted for a time an arbitrary number of cycles ahead.
  Returns a map with :expected-x, :expected-y, and :expected-region set."
  ([tdata old-tdata params]
   (predict-location tdata old-tdata params 1))
  ([tdata old-tdata params cycles-ahead]
   (let [x (:x tdata)
         y (:y tdata)
         region (:region tdata)
         delta-x-queue (:delta-x-queue tdata)

         dx (and (>= (count delta-x-queue) (:min-delta-queue-count params))
                 (sequence-mean (take (:min-delta-queue-count params) delta-x-queue)))
         dy (and dx (sequence-mean (take (:min-delta-queue-count params)
                                         (:delta-y-queue tdata))))

         ;;          dx (or (:delta-x tdata) 0)
         ;;          dy (or (:delta-y tdata) 0)
         next-x (and dx (+ x (* dx cycles-ahead)))
         next-y (and dy (+ y (* dy cycles-ahead)))]
     (if (and region next-x next-y)
       {:expected-x next-x
        :expected-y next-y
        :expected-region (reg/translate-center-to region {:x next-x :y next-y})}
       {}))))

(defn- update-precise-directions
  "Returns a map with updated :precise-x-dir and :precise-y-dir values
  computed based on :precise-delta-x and :precise-delta-y. Does not return
  these values if there's been a recent direction change."
  [tdata old-tdata params]
  (let [precise-delta-x (:precise-delta-x tdata)
        precise-delta-y (:precise-delta-y tdata)]
    (if (and precise-delta-x (not (:direction-change? tdata)))
      {:precise-x-dir
       (cond
         (< (math/abs precise-delta-x) min-delta)
         0
         (> precise-delta-x 0)
         1
         :else
         -1)
       :precise-y-dir
       (cond
         (< (math/abs precise-delta-y) min-delta)
         0
         (> precise-delta-y 0)
         1
         :else
         -1)}
      {})))

(defn- update-min-max-area
  "Updates the min and max area values for this object for the overall period
  we've been tracking it. Returns a map with the updated values."
  [tdata old-tdata params]
  (let [region (:region tdata)
        min-area (:min-area old-tdata)
        max-area (:max-area old-tdata)]
    (when region
      {:min-area
       (if min-area
         (min min-area (reg/area region))
         (reg/area region))
       :max-area
       (if max-area
         (max max-area (reg/area region))
         (reg/area region))})))

(defn- make-fixations
  "Generates a maintenance fixation which includes attended object trajectory information, and
  also generates a collision fixation if the attended object is undergoing a collision."
  [object tdata params source]
  (conj (cond
          (:collision? tdata)
          [{:name "fixation"
            :arguments {:object object :reason "collision"
                        :expected-region (:expected-region tdata)}
            :world nil;;"vstm"
            :source source
            :type "instance"}]
          (:post-collision? tdata)
          [{:name "fixation"
            :arguments {:object object :reason "post-collision"}
            :world nil;;"vstm"
            :source source
            :type "instance"}])

        {:name "fixation"
         :arguments {:object object :reason "maintenance"
                     :delta-x (:delta-x tdata) :delta-y (:delta-y tdata)
                     :precise-delta-x (:precise-delta-x tdata)
                     :precise-delta-y (:precise-delta-y tdata)
                     :precise-x-dir (:precise-x-dir tdata)
                     :precise-y-dir (:precise-y-dir tdata)
                     :precise-delta-count (count (:delta-x-queue tdata))
                     :calculating-delta-queue?
                     (< (count (:delta-x-queue tdata)) (:min-delta-queue-count params))
                     :expected-region (when (:collision? tdata)
                                        (:expected-region tdata))}
         :world nil;;"vstm"
         :source source
         :type "instance"}))

;;This triggers off of either:
;;a) Object-file-binder just encoded an object that's about to go into vstm
;;b) A maintenance fixation, originally based on the above, is currently in the focus
;;If it's a), we're going to retrieve that temporary object and store it in our
;;fixation's object field.  But if it's b), we're going to switch over to the vstm
;;object that was created from it (the one with the same slot value).
;;
;;ALSO: Even if we trigger off a newly encoded object, there may still be a maintenance
;;fixation around from last cycle with relevant information (just not in the focus), so
;;check for that.
(defrecord MaintenanceHighlighter [buffer parameters detect-collision
                                   detect-collision-end trajectory-data]
  Component
  (receive-focus
   [component focus content]
   (let [object (or (when (d/element-matches? focus :name "object" :world nil)
                      focus)
                    (when (d/element-matches? focus :name "fixation" :reason "maintenance")
                      (obj/updated-object (:object (:arguments focus)) content)))

         region (when (and object (-> object :arguments :tracked?))
                  (obj/get-region object content))
         old-tdata @(:trajectory-data component)

         collision?
         (and (:region old-tdata) region
              (or (and (not (:collision? old-tdata))
                       (detect-collision (:region old-tdata) region old-tdata))
                  (and (:collision? old-tdata)
                       (not (detect-collision-end (:region old-tdata) region old-tdata)))))


         new-tdata
         (reduce (fn [data fname] (merge data (fname data old-tdata parameters)))
                 {:region region :collision? collision?}
                 [new-xy update-post-collision-counter new-deltas
                  update-delta-queues detect-direction-change
                  predict-location update-precise-directions update-min-max-area])]
     (if region
       (reset! (:trajectory-data component) new-tdata)
       (reset! (:trajectory-data component) {}))
     (reset! (:buffer component)
             (when region (make-fixations object new-tdata parameters component)))))

  (deliver-result
    [component]
    (set @(:buffer component))))

(defmethod print-method MaintenanceHighlighter [comp ^java.io.Writer w]
  (.write w (format "MaintenanceHighlighter{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->MaintenanceHighlighter (atom ())
                              p
                              (:detect-collision p)
                              (:detect-collision-end p)
                              (atom {}))))
