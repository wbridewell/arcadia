(ns arcadia.component.smooth-pursuit
  "There are at least two sorts of eye movements. Ballistic saccades move
  rapidly to a location, and smooth pursuit follows moving targets by
  matching their velocity across the retinal field. This component enables
  gaze to mimic smooth pursuit eye movements.

  Focus Responsive
    * saccade
    * an element that contains an object

  When the focus element is a saccade request to an object, this component will
  prepare to initiate smooth pursuit upon completion. If the focus contains the
  object that is currently being pursued, then the parameters of the smooth
  pursuit will be adjusted to match the current motion of that object.

  Default Behavior
  Update smooth pursuit information to the current object, alter it to match
  the target of a saccade, or keep track of the target during shift of covert
  attention.

  Produces
   * smooth-pursuit
       includes a :target of the pursuit and that targets current acceleration
       in the x axis (:x-acc) and in the y axis (:y-acc).

  NOTE: This implementation uses basic parameters for smooth pursuit that
  include
    (a) a multiplier applied to the current eye velocity, and
    (b) a multiplier applied to the retinal slip
  In theory, if both multipliers were 1, the system would immediately match
  the target's velocity, but in practice lag may affect this.

  There are different parameters that operate when initiating smooth pursuit
  after saccading to an object not being pursued. These are closer to 1
  because the saccade gives more time to adjust them (not just a single cycle)."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [general :as g] [gaze :as gaze] [objects :as obj]]))

;; NOTE: References to "scan" are based on a model that is in progress.

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

;; retinal slip is the difference between target and eye velocity
(def ^:private eye_velocity_gain 1.0)
(def ^:private retinal_slip_gain 0.15)

(def ^:private eye_velocity_saccade_gain 1.0)
(def ^:private retinal_slip_saccade_gain 0.8)

(defn- update-pursuit
  "Update the velocities of an ongoing smooth pursuit."
  [x-vel y-vel gx-vel gy-vel object sensor]
  (let [new-gx-vel (+ (* gx-vel eye_velocity_gain)
                      (* (- x-vel gx-vel) retinal_slip_gain))
        new-gy-vel (+ (* gy-vel eye_velocity_gain)
                      (* (- y-vel gy-vel) retinal_slip_gain))]
    {:name "smooth-pursuit"
     :arguments {:x-acc (gaze/velocity->seconds (- new-gx-vel gx-vel) sensor)
                 :y-acc (gaze/velocity->seconds (- new-gy-vel gy-vel) sensor)
                 :target object}
     :world nil
     :type "action"}))

(defn- saccade->pursuit
  "Prepare to engage smooth pursuit after saccading to an object."
  [saccade content sensor]
  (let [new-gx-vel (+ (* (-> saccade :arguments :gx-vel) eye_velocity_saccade_gain)
                      (* (-> saccade :arguments :x-RS) retinal_slip_saccade_gain))
        new-gy-vel (+ (* (-> saccade :arguments :gy-vel) eye_velocity_saccade_gain)
                      (* (-> saccade :arguments :y-RS) retinal_slip_saccade_gain))]
    {:name "smooth-pursuit"
     :arguments {:x-acc (gaze/velocity->seconds (- new-gx-vel (-> saccade :arguments :gx-vel)) sensor)
                 :y-acc (gaze/velocity->seconds (- new-gy-vel (-> saccade :arguments :gy-vel)) sensor)
                 :target (obj/updated-object (-> saccade :arguments :target) content)}
     :world nil
     :type "action"}))

(defn- target->pursuit
  "When smooth pursuit is broken off due to a shift of covert attention,
  continue to keep track of the last pursuit target."
  [object]
  {:name "smooth-pursuit"
   :arguments {:target object}
   :world nil
   :type "action"})

(defn- velocity-in-dps
  "Converts velocities stored as pixels into corresponding degrees per second."
  [fixation sensor]
  (if (and (-> fixation :arguments :object) (-> fixation :arguments :delta-x))
    [(gaze/pixels-incr->degrees-second (-> fixation :arguments :delta-x) sensor)
     (gaze/pixels-incr->degrees-second (-> fixation :arguments :delta-y) sensor)]
    [nil nil]))

(defrecord SmoothPursuit [buffer sensor]
  Component
  (receive-focus
    [component focus content]
    (let [fixation (g/find-first #(and (= (:name %) "fixation")
                                       (= (-> % :arguments :reason) "maintenance"))
                                 content)
          fixation-target (-> fixation :arguments :object (obj/updated-object content))
          pursuit-target (-> (g/find-first #(= (:name %) "smooth-pursuit") content)
                             :arguments :target (obj/updated-object content))
          saccade (and (= (:name focus) "saccade") focus)
          focus-object (-> focus :arguments :object (obj/updated-object content))
          gaze (g/find-first #(and (= (:name %) "old-gaze")
                                   (not (-> % :arguments :saccade?)))
                             content)
          [x-vel y-vel] (velocity-in-dps fixation sensor)]

      (cond
       ;; Currently pursuing an object, visual attention is being guided to
       ;; that object through a maintentance fixation, there is existing velocity
       ;; information, and a saccade has not begun.
        (and pursuit-target (= pursuit-target fixation-target)
             (not saccade) x-vel (-> gaze :arguments :x-vel)
             (or (nil? focus-object) (= pursuit-target focus-object)))
        (reset! (:buffer component) (update-pursuit x-vel y-vel
                                                    (-> gaze :arguments :x-vel)
                                                    (-> gaze :arguments :y-vel)
                                                    fixation-target sensor))

       ;; Saccading to an object, so start smooth pursuit once the ballistic
       ;; saccade completes.
        (and saccade (not (= "scan" (-> saccade :arguments :target :name))))
        (reset! (:buffer component) (saccade->pursuit saccade content sensor))

       ;; Saccading to a scan location, so there is nothing to pursue.
        saccade
        (reset! (:buffer component) nil)

       ;; Accommodate shifts of covert attention. If the pursuit target isn't
       ;; maintained, then there's no way to come back to it after the covert
       ;; shift.
        pursuit-target
        (reset! (:buffer component) (target->pursuit pursuit-target))

       ;; No need for smooth pursuit.
        :else
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method SmoothPursuit [comp ^java.io.Writer w]
  (.write w (format "SmoothPursuit{}")))

(defn start [& {:as args}]
  (->SmoothPursuit (atom ()) (:sensor (merge-parameters args))))
