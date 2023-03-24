(ns arcadia.component.scanner
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.stable-viewpoint :as sensor]
            [arcadia.utility.geometry :as geo]
            (arcadia.utility [scan :refer [scan-context extrapolate-loc obj-velocity time-to-collision]]
                             [objects :as obj]
                             [vectors :as vec]
                             [gaze :as gaze]
                             [general :as g]
                             [descriptors :as d])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;scanner
;;;
;;;Performs a covert attention scan along a trajectory, determining
;;;if and where a target is hit.
;;;
;;;Eye movements may follow covert attention, here as elsewhere.

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

; this parameter controls linear radius growth
   ;; by default, scans have a constant radius.
(def ^:parameter radius-growth
  "the linear radius growth for the scan, 0 indicates constant size."
  0)

;; By default, don't reflect scans off of screen boundaries.
;; Set these parameters to the appropriate x/y values to
;; enable reflection off of border walls/screen edges.
(def ^:parameter reflect-top
  "y position of top border if scans are to reflect off screen boundaries" nil)
(def ^:parameter reflect-bottom
  "y position of bottom border if scans are to reflect off screen boundaries" nil)
(def ^:parameter reflect-right
  "x position of right border if scans are to reflect off screen boundaries" nil)
(def ^:parameter reflect-left
  "x position of left border if scans are to reflect off screen boundaries" nil)

(def ^:parameter reflection-uncertainty
  "multiply the radius growth by this factor whenever the scan reflects off of a wall"
  2.0)


(def ^:private TERMINATE-SCAN :TERMINATE-SCAN)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Code for computing the score for an scanning intersection.

(defn- score-intersect
  "Create a score for the intersect between the scan and a static target.
  The score is the percentage of space +/- the scan radius around
  the intersect that lies within the target's bounds."
  ;; This intersection is defined as the space +/ radius
  ;;around intersect. Return what percentage of that space lies within
  ;;the target's bounds (between its min-val and max-val).
  [intersect targ-region {:keys [dimension pixel-radius]}]
  (let [[min-val max-val val]
        (if (= dimension "horizontal")
          [(geo/min-y targ-region) (geo/max-y targ-region) (second intersect)]
          [(geo/min-x targ-region) (geo/max-x targ-region) (first intersect)])]
    (println :score-intersect :intersect val :min min-val :max max-val :radius pixel-radius intersect)
    ;;Either the intersect is between min and max val, it's below min-val, or it's
    ;;above max-val. Calculate the the percentage of the space for each.
    (cond
      (g/between? val min-val max-val)
      (+ (* 0.5 (min 1 (/ (- max-val val) pixel-radius)))
         (* 0.5 (min 1 (/ (- val min-val) pixel-radius))))

      (<= val min-val)
      (* 0.5 (- 1 (min 1 (/ (- min-val val) pixel-radius))))

      (>= val max-val)
      (* 0.5 (- 1 (min 1 (/ (- val max-val) pixel-radius)))))))

(defn score-trace-intersect
  "Create a score for the intersect between the scan and the previously
  made scan trace of a moving target. The score is:
        1 - (d-expected - d-min)/d-min, where
  d-expected is the distance between the intersect and the target's expected
             location at the time of intersect, and
  d-min is minimum distance between the start and target (the sum of their radii)"
  ;; if the target is moving, score the intersect using its location roughly at the time of impact
  [intersect trace {:keys [start target dimension radius]}]
  ;(println :SCORE-INTERSECT intersect dimension (time-to-collision start intersect))
  (- 1.0 (g/epsilon (geo/distance intersect
                                  (extrapolate-loc target (time-to-collision start intersect) trace dimension))
                    (+ (-> start :arguments :region :radius)
                       (-> target :arguments :region :radius)))))



(defn- make-scan
  "Create a new interlingua representing the ongoing process of a visual scan."
  [arguments]
  {:name "scan"
   :arguments (assoc arguments :ongoing? true)
   :world nil
   :type "action"})

(defn- make-intersect
  "Create a new interlingua representing the intersection of the scan path
  and either the target, or a screen edge."
  [intersection score arguments]
  {:name "scan-intersection"
   :arguments (assoc (dissoc arguments :ongoing?)
                     :intersection intersection
                     :score score
                     :ttc (time-to-collision (:start arguments) intersection))
   :world nil
   :type "relation"})


(defn- intersect
  "Calculate the point of intersection between the line segment [(x-prev, y-prev) (x, y)]
  and the bound, given the dimension of the scan. There are four conditions:

  1.) The line segment (x-prev, y-prev) to (x, y) crosses the target bound-
      return the point of intersection.
  2.) The current point (x,y) is outside the boundary of the entire image-
      return the point of intersection between the line segment and the screen edge.
  3.) The line segment (x-prev, y-prev) (x, y) does not intersect the bound,
      but is heading towards it- return nil. The scan is not yet complete.
  4.) The line segment (x-prev, y-prev) (x, y) does not intersect the bound,
      and is heading away from it- return the error code :TERMINATE-SCAN.
      This should not normally occur, but may result if a scan is started
      in the wrong direction."
  [bound image-max-x image-max-y {:keys [dimension x-prev y-prev x y]}]
  (println :intersect x-prev y-prev x y bound dimension)
  (cond
    (and (= dimension "horizontal") (g/between? bound x-prev x))
    [bound (vec/interpolate bound [x-prev y-prev] [x y] true)]

    (and (= dimension "vertical") (g/between? bound y-prev y))
    [(vec/interpolate bound [x-prev y-prev] [x y] false) bound]

    ;; intersection with the screen's edge
    (and (= dimension "horizontal") (neg? y)) [(vec/interpolate 0 [x-prev y-prev] [x y] false) 0]
    (and (= dimension "vertical") (neg? x)) [0 (vec/interpolate 0 [x-prev y-prev] [x y] true)]
    (and (= dimension "horizontal") (> y image-max-y)) [(vec/interpolate image-max-y [x-prev y-prev] [x y] false) image-max-y]
    (and (= dimension "vertical") (> x image-max-x)) [image-max-x (vec/interpolate image-max-x [x-prev y-prev] [x y] true)]

    ;;UPDATE to address an error condition. This is only coming up
    ;;due to a segmentation issue in Causal 18, so maybe someday it can be removed.
    ;; Return :TERMINATE-SCAN as an error signal.
    (or (and (= dimension "horizontal")
             (or (and (< x x-prev) (< x-prev bound) (< x bound))
                 (and (> x x-prev) (> x-prev bound) (> x bound))
                 (= x x-prev)))
        (and (= dimension "vertical")
             (or (and (< y y-prev) (< y-prev bound) (< y bound))
                 (and (> y y-prev) (> y-prev bound) (> y bound))
                 (= y y-prev))))
    TERMINATE-SCAN))


;;----------------------- Scanning towards moving target------------------------
(defn- collision-point
  "Given the scan's line segment, its transformed velocity vector, and
  the closest point to the target along the transformed velocity vector,
  calculate the actual point of collision between the start and target."
  [x0 y0 x1 y1 vel r-dist p dist]
  ;; un-transform the velocity vector to find the collision point
  (vec/+ [x0 y0] (vec/scalar* (vec/- [x1 y1] [x0 y0])
                                 ;; transformed collision point's percent distance
                                 ;; along the transformed velocity vector
                              (/ (vec/distance [x0 y0]
                                      ;; collision point in transformed space
                                               (vec/- p (vec/resize vel (Math/sqrt (max 0 (- (* r-dist r-dist) (vec/norm-sq dist)))))))
                                 (vec/norm vel)))))


(defn- trace-intersect
  "Calculate the point of intersection between the scan path and
  the target's pre-determined scan trace. This involves several steps:

  - transform start velocity by target's velocity (so target appears static)
  - calculate point p on this transformed segment closest to the target
  - If the start object at p overlaps the target, a collision will happen.
    If the p is close to the target, a collision might happen.
    Otherwise a collision will not yet happen, so keep scanning."
  [image-max-x image-max-y target-reg trace {:keys [x-prev y-prev x y start target radius dimension]}]
  (println :trace-intersect [x-prev y-prev] [x y] dimension)
  (cond
    (or (neg? x-prev) (neg? y-prev) (> x-prev image-max-x) (> y-prev image-max-y))
    TERMINATE-SCAN

    :else
    (let [;; extrapolate the target to where it should be around this time
          new-target-loc (extrapolate-loc (geo/center target-reg) (obj-velocity target trace)
                                          (time-to-collision start [x-prev y-prev]) trace dimension)

          ;; start and target collide if their closest distance is smaller than their summed radii
          r-dist (+ (-> start :arguments :region :radius) (:radius target-reg))
          ;; make scan velocity relative to target's velocity (so target appears static)
          vel (vec/- [x y] [x-prev y-prev] (obj-velocity target trace))
          ;; calculate the closest point to the target along vel
          p (vec/closest-point new-target-loc [x-prev y-prev] vel)
          ;; a collision happened if the distance from p to the target is smaller than the radii
          p->target (vec/- new-target-loc p)

          ;; the gap between the start at p and the target
          gap (if (<= (vec/norm p->target) r-dist)
                [0 0]  ;; the start intersects with the target over some area
                (vec/resize p->target (- (vec/norm p->target) r-dist)))

          ;; the factor of target-vel needed to close the gap
          ;; over each dimension, accounting for 0 velocities
          m-vec (mapv #(cond (zero? %1) 0
                             (zero? %2) nil
                             :else (/ %1 %2))
                      gap
                      (obj-velocity target trace))

          ;; the factor of target-vel needed to close the gap
          m (when (not-any? nil? m-vec) (apply min-key #(Math/abs %) m-vec))]
      (cond ;; a collision will happen
        (zero? (vec/norm-sq gap))
        (collision-point x-prev y-prev x y vel r-dist p p->target)

        ;; a collision might happen
        (and m (<= (Math/abs m) 4.0))
        (collision-point x-prev y-prev x y
                         (vec/- vel (vec/* (obj-velocity target trace) m-vec)) r-dist
                         (vec/+ p gap) (vec/- new-target-loc (vec/+ p gap)))))))

(defn- project
  "Returns [(+ v0 vel) vel] if inside the bounds,
   or the reflected values if it crosses the bounds."
  [v0 vel obj-radius lower-bound upper-bound]
  (let [v1 (+ v0 vel)
        ;; shift the bounds to compensate for the object's size
        lb (when lower-bound (+ lower-bound obj-radius))
        ub (when upper-bound (- upper-bound obj-radius))]
    (cond
      ;; reflect off the bottom/left edge
      (and lb (< v1 lb)) [(- (* 2 lb) v1) (- vel)]
      ;; reflect off the top/right edge
      (and ub (> v1 ub)) [(- (* 2 ub) v1) (- vel)]
      ;; no reflection
      :else [v1 vel])))

(defn- reflected?
  "Returns true if the new scan velocities are not equal to the old ones"
  [x-vel y-vel args]
  (or (not= x-vel (:x-vel args))
      (not= y-vel (:y-vel args))))

(defn- growth-rate
  "Determines the growth in the scan radius per cycle. The
   rate is proportional to the speed of the scan (so that radius
   expansion is proportionally the same for different speeds)"
  [r g x-vel y-vel args]
  (* (or g
         (* r (:radius-growth args) (vec/norm [x-vel y-vel])))
     (if (reflected? x-vel y-vel args) (:reflection-uncertainty args) 1.0)))

(defn- expand
  "Increase (or decrease) the scan radius by growth
   with a lower bound of 0.0"
  [r growth]
  (max (+ r growth) 0))

(defn- bound
  "Calculates the bounding value of the scan. If the scan is horizontal,
  the bound is the x value of targ-rect closest to the scan's current location.
  Otherwise the bound is the y value of targ-rect closest to the scan's current location.

  If targ-rect is nil, set the bound to -1"
  [targ-rect {:keys [dimension x-vel y-vel]}]
  (if (nil? targ-rect) -1
      (cond
        (and (= dimension "horizontal") (pos? x-vel)) (geo/min-x targ-rect)
        (= dimension "horizontal") (geo/max-x targ-rect)
        (pos? y-vel) (geo/min-y targ-rect)
        :else (geo/max-y targ-rect))))

(defn- find-target
  "If target is nil, the scan must continue until it hits someting.
  This function returns the closest object within the scan region."
  [start scan content]
  (let [r (obj/get-region scan content)]
    (some->> (obj/get-vstm-objects content)
             (remove #{start})
             (filter #(or (geo/intersect? r (obj/get-region % content))
                          (geo/contains? r (obj/get-region % content))))
             seq
             (apply min-key #(geo/sq-distance (geo/center r)
                                              (geo/center (obj/get-region % content)))))))

;;Given an existing scan, move x and y forward by the amounts specified in x-vel
;;and y-vel.  Then, check if we're now intersecting the appropriate bound of our
;;target's rectangle.  If we are, put out a "scan-intersection" indicating how confident
;;we are that our intersection was along the target (and not above or below it, e.g., if this
;;was a horizontal scan as indicated by dimension = "horizontal"). If we aren't, then put out a "scan"
;;with :ongoing? set to true. If this continues to receive attention, we'll continue scanning.
(defn- update-scan [scan content sensor parameters]
  (let [;; project the start's velocity one more step, taking wall bounces into account
        [x1 x-vel] (project (:x (:arguments scan)) (:x-vel (:arguments scan))
                            (-> scan :arguments :start :arguments :region :radius)
                            (:reflect-left parameters)
                            (:reflect-right parameters))
        [y1 y-vel] (project (:y (:arguments scan)) (:y-vel (:arguments scan))
                            (-> scan :arguments :start :arguments :region :radius)
                            (:reflect-top parameters)
                            (:reflect-bottom parameters))
        new-args
        (-> (merge parameters (:arguments scan))
            (g/assoc-fn :start #(obj/updated-object (:start %) content)
                        :target #(or (obj/updated-object (:target %) content)
                                     (find-target (:start %) scan content))
                        :growth #(growth-rate (:radius %) (:growth %) x-vel y-vel %)
                        :pixel-growth #(growth-rate (:pixel-radius %) (:pixel-growth %) x-vel y-vel %)
                        :radius #(expand (:radius %) (:growth %))
                        :pixel-radius #(expand (:pixel-radius %) (:pixel-growth %)))
            (assoc :x-prev (:x (:arguments scan)) :y-prev (:y (:arguments scan))
                   :x x1 :y y1 :x-vel x-vel :y-vel y-vel))

        targ-reg (obj/get-estimated-region (:target new-args) content)

        ;; if the target is moving and we've already estimated its trajectory,
        ;; use that trajectory in testing for intersections
        target-trace (when (:target new-args)
                       (g/find-first #(and (= (:name %) "scan-trace")
                                           (= (:world %) "working-memory")
                                           (= (:context (:arguments %)) (-> % :arguments :start-descriptor scan-context))
                                           (d/descriptor-matches? (:start-descriptor (:arguments %)) (:target new-args)))
                                     content))

        intersect (if target-trace
                    (trace-intersect (sensor/camera-width sensor) (sensor/camera-height sensor)
                                     targ-reg target-trace new-args)
                    (intersect (bound targ-reg new-args)
                               (sensor/camera-width sensor) (sensor/camera-height sensor)
                               new-args))]

    (println :UPDATE-SCAN (-> scan :arguments :relation :predicate-name) (:context (:arguments scan))
             (:radius new-args) (:pixel-radius new-args) intersect)
    (list (cond
            ;; continue scanning on if there's no intersection
            (nil? intersect)
            (make-scan  new-args)

            ;; if the scan is moving in the wrong direction or not moving at all on the
            ;; appropriate axis, score the nil "intersect" as 0.0 (it will never happen)
            (= intersect TERMINATE-SCAN)
            (make-intersect nil 0.0 new-args)

            :else
            (make-intersect intersect
                            (if target-trace
                              (score-trace-intersect intersect target-trace new-args)
                              (score-intersect intersect targ-reg new-args))
                            new-args))

          ;; if there's an object in the way, output a scan intersection for it
          (when (and (:track-interceptors? new-args)
                     (:target new-args))
            (let [interceptor (find-target (:start new-args) scan content)
                  interceptor-reg (when interceptor (obj/get-estimated-region interceptor content))
                  interceptor-intersect (and interceptor
                                             (geo/not= targ-reg interceptor-reg)
                                             (intersect (bound interceptor-reg new-args)
                                                        (sensor/camera-width sensor) (sensor/camera-height sensor)
                                                        new-args))]
              (when (and interceptor-intersect (not= interceptor-intersect TERMINATE-SCAN))
                ;; NOTE: throw away the relation and context of the scan, because the
                ;; interception represents a different possibility than the one we're looking for
                (make-intersect interceptor-intersect
                                (score-intersect interceptor-intersect interceptor-reg new-args)
                                (assoc new-args :target interceptor :relation nil :context nil))))))))


(defrecord Scanner [buffer scan-queue sensor parameters]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component)
            (when (= (:name focus) "scan")
              (update-scan focus content (:sensor component) (:parameters component))))

    (reset! (:scan-queue component)
            (when (= (:name focus) "scan")
              (gaze/update-delay-queue focus "old-scan" @(:scan-queue component))))

    (doall (map #(->> % :arguments :score (println :scanner-score))
                (filter (comp :score :arguments) @(:buffer component)))))
  (deliver-result
    [component]
    (into () (conj @(:buffer component)
                   (gaze/delay-queue-output @(:scan-queue component))))))

(defmethod print-method Scanner [comp ^java.io.Writer w]
  (.write w (format "Scanner{}")))

;; The following optional parameters allow for scans to
;; reflect off of screen edges-
;;   :reflect-top, :reflect-bottom - y values to reflect scans from
;;   :reflect-right, :reflect-left - x values to reflect scans from
;;
;;   :radius-growth is a parameter determining the rate the scan
;;   window expands every cycle as a fraction of the original radius
(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->Scanner (atom nil) (atom nil) (:sensor p) p)))
