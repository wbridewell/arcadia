(ns
  ^{:doc "Utility functions for creating and evaluation visual scans.
The functions scan and relation-scan generate scan requests
with the following parameters:

:x, y- the starting location of the scan
:x-vel, y-vel- the speed of the starting object (used for scan direction)
:relation- the relation being verified through the scan
:context- the context of the relation being verified
:dimension- the direction of the scan, \"horizontal\" or \"vertical.\"
:track-interceptors?- if true, the scan will return scan intersections
              for other objects other than the target inside the scan path
:scan-speed->radius- a linear conversion factor from the scan speed to the scan radius
:sensor- the sensor used for vision (for degree/pixel conversion)
"}
  arcadia.utility.scan
  (:require (arcadia.utility [vectors :as vec]
                             [relations :as rel] [general :as g] [gaze :as gaze] [objects :as obj] [descriptors :as d])
            [arcadia.vision.regions :as reg]
            arcadia.architecture.registry))

(def default-parameters
  "Default parameters for scan evaluation:
  :upper-bound- the upper bound for a relation to be considered uncertain; if
                the scan score is above this bound, it is considered true.
  :lower-bound- the lower bound for a relation to be considered uncertain; if
                the scan score is below this bound, it is considered false."
  {:dimension "horizontal"
   :track-interceptors? false
   :scan-speeds [240 120 80]
   :speed 240
   :scan-speed->radius 0.0125
   :upper-bound 0.99
   :lower-bound 0.01})

(defn scan-context
  "Creates a unique string identifying the context for a scan predicting the
  outcome of the scan's start."
  ([start-descriptor]
   (str "predict-" (:label start-descriptor)))
  ([start object-descriptors]
   (scan-context (d/get-descriptor start object-descriptors))))


(defn scan
  "A generic function for creating a scan requests.
  Required keyword arguments:
  :x, y- the starting location of the scan
  :x-vel, :y-vel- the speed of the starting object in pixels/cycle

  All other parameters are described in the arcadia.utility.scan
  namespace documentation."
  [sensor & {:keys [start target relation context
                    speed scan-speed->radius dimension
                    track-interceptors? x y x-vel y-vel]
             :or {dimension (:dimension default-parameters)
                  speed (:speed default-parameters)
                  scan-speed->radius (:scan-speed->radius default-parameters)
                  track-interceptors? (:track-interceptors? default-parameters)}}]
  (when (and x y x-vel y-vel (not (and (zero? x-vel) (zero? y-vel))))
    (let [x-vel-s (gaze/velocity->seconds x-vel sensor)
          y-vel-s (gaze/velocity->seconds y-vel sensor)
          speed-factor (/ speed (gaze/pixels-incr->degrees-second (vec/norm [x-vel-s y-vel-s]) sensor))
          radius (* speed scan-speed->radius)]
      {:name "scan"
       :arguments {:x x :y y
                   :x-vel (* x-vel-s speed-factor) :y-vel (* y-vel-s speed-factor)
                   :start start :target target
                   :speed speed :dimension dimension
                   :radius radius :pixel-radius (gaze/degrees->pixels radius sensor)
                   :track-interceptors? track-interceptors?
                   :relation relation :context context}
       :world nil
       :type "action"})))

(defn- scan-in-progress?
  "Returns true iff there is a scan in progress for a relation between the start and target, or
  if the results from a recent scan for this relation are being processed."
  [relation start-descriptor target-descriptor context content]
  (or (d/some-element content :name #(or (= % "scan") (= % "scan-intersection"))
                      :relation relation :context context
                      :start (partial d/descriptor-matches? start-descriptor))
      (d/some-element content :name "memory-update"
                      :new (partial some (partial d/descriptor-matches?
                                                  (rel/instance-descriptor relation
                                                                           :arguments [start-descriptor target-descriptor]
                                                                           :context context))))
      (d/some-element content :name "instantiate"
                      :relation relation :context context
                      :objects (partial d/descriptors-match? [start-descriptor target-descriptor]))
      (d/some-match (rel/instance-descriptor relation
                                             :arguments [start-descriptor target-descriptor]
                                             :context context
                                             :world nil)
                    content)
      (d/some-element content :name "scan-trace" :world nil
                    :relation relation :context context
                    :start-descriptor start-descriptor :target-descriptor target-descriptor)
      (d/some-element content :name #(or (= % "memory-update") (= % "memorize"))
                      :element #(d/element-matches? % :name "scan-trace"
                                                    :relation relation :context context
                                                    :start-descriptor start-descriptor
                                                    :target-descriptor target-descriptor))))

(defn relation-scan
  "This function creates scan requests in service of verifying a
  relation between the start and the target. These scan requests will follow
  the order of scan speeds in the :scan-speeds parameter: these speeds should
  be descending (wider/faster scans are executed first, followed by
  smaller/slower scans). Importantly, the scan request is not output if there
  is a corresponding scan already going on. This stops ARCADIA from restarting scans.

  This function takes the following parameters in addition to the basic scan
  parameters listed in the arcadia.utility.scan namespace documentation:
  :start-descriptor- a descriptor of the start object (used for finding previous scan results)
  :target-descriptor-  a descriptor of the target object (used for finding previous scan results)
  :scan-speeds- a sequence of scan speeds to progress through. This sequence should
                be descending (fast/course scans followed by slow/fine grained scans)
  "
  [start target content & {:keys [context scan-speeds scan-speed->radius
                                  x y x-vel y-vel sensor relation context
                                  start-descriptor target-descriptor
                                  dimension track-interceptors?]
                           :or {x (-> start (obj/get-region content) reg/center :x)
                                y (-> start (obj/get-region content) reg/center :y)
                                x-vel (-> start :arguments :precise-delta-x)
                                y-vel (-> start :arguments :precise-delta-y)
                                start-descriptor (d/object-descriptor)
                                target-descriptor (d/object-descriptor)
                                context (scan-context start-descriptor)
                                dimension (:dimension default-parameters)
                                track-interceptors? (:track-interceptors? default-parameters)
                                scan-speeds (:scan-speeds default-parameters)
                                scan-speed->radius (:scan-speed->radius default-parameters)
                                sensor (-> (d/first-element content :name "gaze") :arguments :sensor)}}]
  ;; don't do anything if the relevant relation is being updated
  (when-not (scan-in-progress? relation start-descriptor target-descriptor context content)
    (let [instances (d/filter-matches (rel/instance-descriptor relation
                                                               :arguments [start-descriptor target-descriptor]
                                                               :context context)
                                    content)
          prev-speed (some->> instances
                              (map (comp :speed :arguments first :justifications :arguments))
                              (remove nil?) seq (apply min))
          next-speed (when (and prev-speed (not (= prev-speed (last scan-speeds))))
                       (first (last (partition-by #{prev-speed} scan-speeds))))]
      (cond
        ;; slower scan
        (and (> (count instances) 1) next-speed)
        (scan sensor :start start :target target :relation relation :context context
              :speed next-speed :scan-speed->radius scan-speed->radius
              :x x :y y :x-vel x-vel :y-vel y-vel)

        ;; first scan
        (empty? instances)
        (scan sensor :start start :target target :relation relation :context context
              :speed (first scan-speeds) :scan-speed-radius scan-speed->radius
              :x x :y y :x-vel x-vel :y-vel y-vel)))))

(defn score->truth-values
  "Given a scan score and upper/lower bounds, determines the
  truth value of the scan's relation. A score above upper-bound
  means the relation is true, a score below lower-bound means the
  relation is false, and finally an intermediate score means the
  relation could be either true or false."
  [score & {:keys [upper-bound lower-bound]
            :or {upper-bound (:upper-bound default-parameters)
                 lower-bound (:lower-bound default-parameters)}}]
  (cond
    (> score upper-bound) [true]
    (< score lower-bound) [false]
    :else [true false]))

(defn scan-intersection->relation
  "Given a scan-intersection, determines whether its relation holds
  and returns the appropriate instantiation request. Takes the parameters
  :upper-bound- the upper-bound on the scan score for uncertainty.
                If the score is above this value, it is deemed true.
  :lower-bound- the lower-bound on the scan score for uncertainty.
                If the score is above this value, it is deemed false."
  [scan-intersection content & {:as params}]
  (rel/instantiate (-> scan-intersection :arguments :relation)
                   (-> scan-intersection :arguments ((juxt :start :target)))
                   (g/apply-map score->truth-values (-> scan-intersection :arguments :score) params)
                   (-> scan-intersection :arguments :context)
                   [scan-intersection]))

(defn done-scanning?
  "Determines whether scanning for a relation is complete. This is true iff the
  relation only has one certain value, or if the last scan had the slowest speed.

  This function should be called in one of two ways: either including the keywords
  :relation- the relation to being verified by the scanning procedure
  :start-descriptor- a descriptor of the scan's starting object
  :target-descriptor- a descriptor of the scan's target object
  :context- the context of the relation being verified by scanning.

  OR the keyword :instance, which is an instance of such a relation.
  Additionally, relation-specific :scan-speeds can be added as a parameter."
  [content & {:keys [instance relation start-descriptor
                     target-descriptor context scan-speeds]
              :or {scan-speeds (:scan-speeds default-parameters)
                   relation (-> instance :arguments :relation)
                   start-descriptor (-> instance :arguments :argument-descriptors first)
                   target-descriptor (-> instance :arguments :argument-descriptors second)
                   context (-> instance :arguments :context)}}]
  (and (not (scan-in-progress? relation start-descriptor target-descriptor context content))
       (let [instances (d/filter-matches (rel/instance-descriptor relation :context context
                                                                  :world "working-memory"
                                                                  :arguments [start-descriptor
                                                                              target-descriptor])
                                         content)]
         (or (= 1 (count (rel/truth-values instances)))
             (some #{(last scan-speeds)}
                   (map (comp :speed :arguments first :justifications :arguments) instances))))))

(defn obj-velocity
  "Extract an object's velocity at the time it was scanned.

  This function avoids problems with object velocities changing or being overwritten,
  which can cause exceptions and errors in velocity-related math."
  [obj scan]
  (or (-> scan :arguments :obj-velocity)
   (let [v [(or (-> obj :arguments :precise-delta-x)
                (-> scan :arguments :start :arguments :precise-delta-x)
                (some->> scan :arguments :scans (g/find-first #(-> % :arguments :start :arguments :precise-delta-x))
                         :arguments :start :arguments :precise-delta-x))
            (or (-> obj :arguments :precise-delta-y)
                (-> scan :arguments :start :arguments :precise-delta-y)
                (some->> scan :arguments :scans (g/find-first #(-> % :arguments :start :arguments :precise-delta-y))
                         :arguments :start :arguments :precise-delta-y))]]
     (when (not-any? nil? v) v))))


(defn time-to-collision
  "Calculates the time-to-collision (in cycles) for an object to arrive at intersection point."
  ([obj intersection]
   (time-to-collision (-> obj :arguments :region :center)
                      (obj-velocity obj intersection)
                      intersection))
  ([[x y] [dx dy] intersection]
   (when (and x y dx dy intersection
              (not (and (zero? dx) (zero? dy))))
     (/ (reg/distance {:x x :y y} intersection) (vec/norm [dx dy])))))

(defn extrapolate-loc
  "Extrapolates an object's position over time given its velocity and current location."
  ([obj ttc trace dimension]
   (extrapolate-loc (-> obj :arguments :region :center)
                    (obj-velocity obj trace) ttc trace dimension))
  ([[x y] [dx dy] ttc trace dimension]
   (when (and x y dx dy ttc trace dimension)
     (let [bound (if (= dimension "horizontal")
                   (+ x (* ttc dx))
                   (+ y (* ttc dy)))
           seg (->> trace :arguments :centers (partition 2 1)
                    (g/find-first #(if (= dimension "horizontal")
                                     (g/between? bound (first (first %)) (first (second %)))
                                     (g/between? bound (second (first %)) (second (second %))))))
           intersect (when seg (vec/interpolate bound (first seg) (second seg) (= dimension "horizontal")))]
       (cond
         (and (= dimension "horizontal") (= dx 0)) [x (+ y (* ttc dy))]
         (and (= dimension "vertical") (= dy 0)) [(+ x (* ttc dx)) y]
         (and (= dimension "horizontal") seg) [bound intersect]
         seg [intersect bound])))))
