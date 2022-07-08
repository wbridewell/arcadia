(ns arcadia.component.gaze-updater
  "When modeling psychological studies or simulating an embodied system, it is
  useful to incorporate eye movement. This component manages eye position and
  movement for ballistic saccades and smooth pursuit. Efforts have been made
  to match the sort of behavior observed in humans (as opposed to what might)
  be possible in any particular robotic system.

  Focus Responsive
    * saccade

  When the focus element is a saccade request, this component will initiate
  eye movement to the corresponding location.

  Default Behavior
  Update information about where the gaze is centered, how fast it is moving,
  and whether it will end by initiating smooth pursuit to follow an object.

  Produces
   * gaze
       includes several arguments that track the current state of the gaze
       :x and :y report the current gaze position in visual degrees
       :pixel-x and :pixel-y report current gaze position in raw pixel value
       :x-acc and :y-acc keep track of the current acceleration
       :x-vel and :y-vel keep track of the current velocity
       :pre-x-vel and :pre-y-vel report the velocity to return to after the
       current saccade is complete (for smooth pursuit)
       :target stores the target object or scan location for the saccade
       :saccading? reports whether the gaze is in the middle of a saccade
       :cycles reports how many cycles of ARCADIA execution are left before
       the current saccade is complete.

  NOTE: References to \"scan\" are based on a model that is in progress.

  NOTE: For now, the x and y values are handled separately, although there is
  evidence that they interact. For example, there are limits to how sharp a
  curve smooth pursuit can make (see, de'Sperati & Viviani, 1997).

  NOTE: Smooth pursuit operates differently from saccades. Specifically, there
  are two conditions for handling pursuit requests:
    (1) The system was not and is not saccading. In that case, the
        gaze accelerations are set to those requested for smooth pursuit.
        :x-vel and :y-vel

    (2) The system was saccading. In that case the velocities after the
        saccade should reflect those requested for smooth pursuit.
        :pre-x-vel and :pre-y-vel"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [descriptors :as d] [objects :as obj] [gaze :as gaze]]))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(def ^:parameter init-x "initial gaze x-position in visual degrees from center screen" 0)
(def ^:parameter init-y "initial gaze y-position in visual degrees from center screen" 0)
(def ^:parameter init-x-vel "initial gaze x-velocity in visual degrees" 0)
(def ^:parameter init-y-vel "initial gaze y-velocity in visual degrees" 0)
(def ^:parameter init-x-acc "initial gaze x-acceleration in visual degrees" 0)
(def ^:parameter init-y-acc "initial gaze y-acceleration in visual degrees" 0)
(def ^:parameter deceleration-rate "amount that speed decelerates on each cycle" 0.5)

(defn- updated-velocities
  "Return a vector containing updated x and y velocities based on the arguments
  of the old gaze element."
  [old-args sensor params]
  [(+ (or (:x-vel old-args) (:init-x-vel params))
      (gaze/velocity->incr (or (:x-acc old-args) (:init-x-acc params)) sensor))
   (+ (or (:y-vel old-args) (:init-y-vel params))
      (gaze/velocity->incr (or (:y-acc old-args) (:init-y-acc params)) sensor))])

(defn- updated-position
  "Return a vector containing updated x and y values based on the current
  velocity and the arguments of the old gaze element."
  [x-vel y-vel old-args sensor params]
  [(+ (or (:x old-args) (:init-x params))
      (gaze/velocity->incr (/ (+ (or (:x-vel old-args) (:init-x-vel params)) x-vel) 2) sensor))
   (+ (or (:y old-args) (:init-y params))
      (gaze/velocity->incr (/ (+ (or (:y-vel old-args) (:init-y-vel params)) y-vel) 2) sensor))])

(defn- updated-accelerations
  "Update smooth pursuit accelerations or set to 0 if a saccade was initiated."
  [pursuit old-args]
  (if (:saccading? old-args)
    [0 0]
    [(or (-> pursuit :arguments :x-acc) 0)
     (or (-> pursuit :arguments :y-acc) 0)]))

(defn- post-saccade-velocities
  "Report the velocities to return to after a saccade, updating them if there
  is an incoming smooth pursuit command."
  [pursuit old-args sensor]
  (if (= (-> old-args :target :name) "scan")
    ;; velocities after saccading to a scan location (i.e., not an object) are 0.
    [0 0]
    [(if (and (:saccading? old-args) (-> pursuit :arguments :x-acc))
       (+ (:pre-x-vel old-args) (gaze/velocity->incr (-> pursuit :arguments :x-acc) sensor))
       (:pre-x-vel old-args))
     (if (and (:saccading? old-args) (-> pursuit :arguments :y-acc))
       (+ (:pre-y-vel old-args) (gaze/velocity->incr (-> pursuit :arguments :y-acc) sensor))
       (:pre-y-vel old-args))]))

(defn- update-gaze [old-args source pursuit sensor params]
  (let [[new-x-vel new-y-vel] (updated-velocities old-args sensor params)
        [x y] (updated-position new-x-vel new-y-vel old-args sensor params)
        cycles (if (:saccading? old-args) (- (:cycles old-args) 1) 0)
        still-saccading? (and (:saccading? old-args) (> cycles 0))
        [pre-x-vel pre-y-vel] (post-saccade-velocities pursuit old-args sensor)
        ;;Do smooth pursuit.
        [x-acc y-acc] (updated-accelerations pursuit old-args)]
    {:name "gaze"
     :arguments {:x-acc x-acc
                 :y-acc y-acc
                 :x-vel (if (and (:saccading? old-args) (not still-saccading?))
                          pre-x-vel
                          new-x-vel)
                 :y-vel (if (and (:saccading? old-args) (not still-saccading?))
                          pre-y-vel
                          new-y-vel)
                 :pre-x-vel (when still-saccading? pre-x-vel)
                 :pre-y-vel (when still-saccading? pre-y-vel)
                 :target (when still-saccading? (:target old-args))
                 :x x
                 :y y
                 :pixel-x (gaze/x-degrees->pixels x sensor)
                 :pixel-y (gaze/y-degrees->pixels y sensor)
                 :cycles cycles
                 :sensor sensor ;;Provide this, in case other stuff in the model
                                ;;needs to directly access the sensor to get
                                ;;information like camera-increment.
                 :saccading? still-saccading?}
     :world nil
     :source source
     :type "instance"}))


;;Takes the updated gaze as input
;;Saves pre-saccadic velocity, then calculates and adds in saccadic velocity
;;Marks this gaze as being a saccade
;;Records time (number of cycles) remaining in the saccade
(defn- initiate-saccade [gaze saccade content source sensor]
  (let [cycles (Math/ceil (gaze/duration->incr (-> saccade :arguments :duration) sensor))
        duration (gaze/duration->seconds cycles sensor)
        x-vel (/ (-> saccade :arguments :x-amp) duration)
        y-vel (/ (-> saccade :arguments :y-amp) duration)]
    {:name "gaze"
     :arguments {:x-acc 0
                 :y-acc 0 ;;Assume instant acceleration
                 :x-vel (+ x-vel (-> gaze :arguments :x-vel))
                 :y-vel (+ y-vel (-> gaze :arguments :y-vel))
                 :pre-x-vel (-> gaze :arguments :x-vel) ;;Save this to go back to it.
                 :pre-y-vel (-> gaze :arguments :y-vel)
                 :x (-> gaze :arguments :x)
                 :y (-> gaze :arguments :y)
                 :pixel-x (-> gaze :arguments :pixel-x)
                 :pixel-y (-> gaze :arguments :pixel-y)
                 :saccading? true
                 :target (obj/updated-object (-> saccade :arguments :target) content)
                 :cycles cycles}
     :world nil
     :source source
     :type "instance"}))

;;If there's no input at all (saccades, pursuit), then decelerate.
(defn- decelerate [gaze sensor params]
  {:name "gaze"
   :arguments {:x-acc
               (gaze/velocity->seconds (- (* (-> gaze :arguments :x-vel) (:deceleration-rate params))
                                     (-> gaze :arguments :x-vel)) sensor)
               :y-acc
               (gaze/velocity->seconds (- (* (-> gaze :arguments :y-vel) (:deceleration-rate params))
                                     (-> gaze :arguments :y-vel)) sensor)
               :x-vel (-> gaze :arguments :x-vel)
               :y-vel (-> gaze :arguments :y-vel)
               :x (-> gaze :arguments :x)
               :y (-> gaze :arguments :y)
               :sensor sensor ;;Provide this, in case other stuff in the model
                              ;;needs to directly access the sensor to get
                              ;;information like camera-increment.
               :pixel-x (-> gaze :arguments :pixel-x)
               :pixel-y (-> gaze :arguments :pixel-y)}})

(defrecord GazeUpdater [buffer gaze-queue sensor parameters]
  Component
  (receive-focus
    [component focus content]
    (let [saccade (and (d/element-matches? focus :name "saccade") focus)
          gaze (d/first-element content :name "gaze")
          suppression (d/first-element content :name "gaze-movement-suppressor")
          updated-gaze (update-gaze (:arguments gaze) component
                                    (d/first-element content :name "smooth-pursuit") sensor parameters)]
      (reset! (:buffer component)
              (cond
                (and suppression (not (-> updated-gaze :arguments :saccading?)))
                (decelerate updated-gaze sensor parameters)

                (and updated-gaze saccade)
                (initiate-saccade updated-gaze saccade content component sensor)

                :else
                updated-gaze))
      (reset! (:gaze-queue component)
              (cons (assoc gaze :name "old-gaze")
                    (take (- gaze/gaze-cycle-lag 1) @(:gaze-queue component))))))

  (deliver-result
   [component]
   #{@(:buffer component) (last @(:gaze-queue component))}))

(defmethod print-method GazeUpdater [comp ^java.io.Writer w]
  (.write w (format "GazeUpdater{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->GazeUpdater (atom nil) (atom nil) (:sensor p) p)))
