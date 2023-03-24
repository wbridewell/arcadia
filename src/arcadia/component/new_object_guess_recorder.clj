(ns arcadia.component.new-object-guess-recorder
  "This is a task-specific component developed for carrying out experiments
  with stimuli for multiple object tracking. In that task, targets for
  tracking are established at the beginning of stimulus presentation, after
  which the targets and distractors are made to look identical except for
  their locations.

  At the end of each video, certain objects will change color, which acts as a
  probe. The task is to report whether these objects were ones that were
  selected as targets at the beginning of stimulus presentation. The driver
  for running the experiments will look to see how many of the probed objects
  were \"new\" (i.e., not tracked) and see if that count matches the
  expectations based on whether the probes were on target or distractor
  objects.

  Focus Responsive
  No.

  Default Behavior
  When there is a newly tracked object or the color changes on a currently
  tracked object, guess whether that object was tracked.

  Produces
    * new-object-guess
         includes an argument :old? that contains the guess of whether this
         argument was tracked, a :probability that under the current
         circumstances the guess would be true, an :index containing the
         current number of guesses, and lists of :tracked and :untracked
         objects."
  (:require [arcadia.sensor.stable-viewpoint :as sensor]
            [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.geometry :as geo]
            [clojure.data.generators :as dgen]
            (arcadia.utility [descriptors :as d]
                             [objects :as obj])))

(def ^:parameter ^:required color "a sensor that provides visual input (required)" nil)
(def ^:parameter sensor "a sensor that provides visual input" nil)
(def ^:parameter guess-mode
  "probability that the guess will be true
     :simple-quad, which is 50/50,
     :complex-quad, which is 50/50 or 66/34 if there's a known target in the
                    same quadrant,
     default, always guess false."
  nil)

(def ^:parameter num-guesses "the number of guesses per trial" 2)

(defn- color-changed-object
  "Returns a vstm object whose color has changed to the specified value."
  [content color]
  (let [equality (d/first-element content :name "visual-equality")]
    (when (and (= (-> equality :arguments :new :arguments :color) color)
               (not (= (-> equality :arguments :old :arguments :color) color)))
      (-> equality :arguments :new))))

(defn- object->quad
  "Returns the quadrant of the screen that contains the object
      1   2
      4   3"
  [object content width height]
  (let [{x :x y :y} (geo/center (obj/get-region object content))]
    (cond
      (and (< x (/ width 2)) (< y (/ height 2)))
      1
      (< y (/ height 2))
      2
      (< x (/ width 2))
      4
      :else
      3)))

(defn- simple-quad-guess
  "If an object was lost in the same quadrant as the new object, then
  guess 50/50 whether the lost object is the same as the other one. The
  return vector includes [guess probability-of-true]."
  [new objects content width height]
  (let [quad (object->quad new content width height)
        untracked-same-quad (filter #(and (not (-> % :arguments :tracked?))
                                          (= quad (object->quad % content width height)))
                                    objects)]
    (if (empty? untracked-same-quad)
      [false 0.0]
      [(> (dgen/double) 0.5) 0.5])))

(defn- complex-quad-guess
  "If an object was lost in the same quadrant as the new object, then
  guess whether the lost object is the same as the other one. The guess
  will be 50/50 unless there is another target in the quadrant, then
  the guess will be 66/34. The return vector includes
  [guess probability-of-true]."
  [new objects content width height]
  (let [quad (object->quad new content width height)
        tracked (filter #(and (not (obj/same-region? new % content))
                              (-> % :arguments :tracked?)
                              (= quad (object->quad % content width height)))
                        objects)
        untracked (filter #(and (not (-> % :arguments :tracked?))
                                (= quad (object->quad % content width height)))
                          objects)]
    (cond
      (empty? untracked) ;;We haven't lost anything. This isn't a tracked object.
      [false 0.0]

      (zero? (count tracked)) ;;We've lost everything, so we don't know.
      [(> (dgen/double) 0.5) 0.5]

      :else  ;;We've lost some things, so probably this isn't a tracked object.
      [(> (dgen/double) 0.66) 0.33])))

(defn- generate-guess
  "If necessary, guess whether the object was a target. Available guessing
  strategies are
       :simple-quad, which is 50/50,
       :complex-quad, which is 50/50 or 66/34 if there's a known target in the
                      same quadrant,
       default, always guess false.
  Returns a vector containing [guess probability-of-true]."
  [new old guess-mode objects content width height]
  (cond
    old  ;Always return true if we recognize the object
    [true 1.0]

    (= guess-mode :simple-quad)
    (simple-quad-guess new objects content width height)

    (= guess-mode :complex-quad)
    (complex-quad-guess new objects content width height)

    :else
    [false 0.0]))

(defn- make-guess
  [new-object old-object index content params]
  (let [objects (obj/get-vstm-objects content)
        [old? probability]
        (generate-guess new-object old-object (:guess-mode params) objects
                        content (:width params) (:height params))]
    {:name "new-object-guess"
     :arguments {:old? old?
                 :probability probability
                 :index index
                 :tracked
                 (count (filter #(and (not (obj/same-region? new-object % content))
                                      (-> % :arguments :tracked?))
                                objects))
                 :untracked
                 (count (filter #(not (-> % :arguments :tracked?)) objects))}
     :world nil
     :type "event"}))

(defn- push-button
  [guess]
  {:name "push-button"
   :arguments {:button-id (if (-> guess :arguments :old?)
                            "old" "new")}
   :world nil
   :type "action"})

(defrecord NewObjectGuessRecorder [buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (let [guesses (d/filter-elements content :name "new-object-guess")
          new-object (-> (d/first-element
                          content :name "visual-new-object"
                          :object #(d/element-matches? % :color (:color parameters)))
                         :arguments :object)
          old-object (color-changed-object content (:color parameters))
          guess (and (or new-object old-object)
                     (make-guess  new-object old-object
                                  (+ 1 (mod (count guesses) (:num-guesses parameters)))
                                  content parameters))]

      ;;Every time we produce a guess, we want to push a button. Additionally,
      ;;we want to remember all the guesses for the curren trial. :num-guesses
      ;;is the number of guesses per trial, so if the number of rememebred
      ;;guesses ever exceeds this value, forget the old guesses.
      (cond
        (and guess
             (>= (count guesses) (:num-guesses parameters)))
        (reset! (:buffer component)
                (list guess (push-button guess)))

        guess
        (reset! (:buffer component)
                (concat (list guess (push-button guess))
                        guesses))

        :else
        (reset! (:buffer component) guesses))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method NewObjectGuessRecorder [comp ^java.io.Writer w]
  (.write w (format "NewObjectGuessRecorder{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)
        w (if (:sensor p) (sensor/camera-width (:sensor p)) 0)
        h (if (:sensor p) (sensor/camera-height (:sensor p)) 0)]
    (->NewObjectGuessRecorder (atom nil) (assoc p :width w :height h))))
