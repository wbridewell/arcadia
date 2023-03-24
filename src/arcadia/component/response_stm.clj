(ns arcadia.component.response-stm
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.display :as display]))

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

;; who knows what this should be. eventually we'll want to do a parameter sweep to determine
;; what makes the most sense. for now, we start out with something that's likely much too low.
(def ^:parameter threshold "the numerical threshold that responses have to breach to be produced"
  10)

;; to manage inhibition, i need to store semantics and response pairs
;; {source {response activation}}
;; if source is the same and response is the same, activation should increase by strength
;; if source is the different and response is same, activation should increase by strength/threshold
;; if source is the different and response is different, activation should decrease by strength/(0.5*threshold)
;; if source is same and response is different, then we have a different response

(defn- element-to-semantic-data [x]
  {:property (-> x :arguments :property)
   :value ((-> x :arguments :property) (:arguments x))
   :source (-> x :arguments :path)
   :strength (-> x :arguments :strength)})

(defn- semantic-distance 
  "Returns an inhibition level between two elements based on their categorical 
   distance from each other."
  [x y]
  (cond (and (= (:value x) (:value y))
             (= (:property x) (:property y))
             (= (:source x) (:source y)))
        0

        ;; This is the neutral stimulus case
        (and (not= (:property x) (:property y))
             (not= (:value x) (:value y))
             (not= (:source x) (:source y)))
        2.5

        ;; The congruent stimulus case.
        (and (= (:property x) (:property y))
             (= (:value x) (:value y))
             (not= (:source x) (:source y)))
        1.25

        ;; The incongruent stimulus case
        (and (= (:property x) (:property y))
             (not= (:value x) (:value y))
             (not= (:source x) (:source y)))
        5))

(defn- task-inhibition [source response-type inhibit-conflict?]
  (cond (and (not= response-type source) inhibit-conflict?) 0.5
        (not= response-type source) 1
        :else 0))

(defn- semantic-support [x semantic-data response-type inhibit-conflict? threshold]
  (reduce - 
          ;; response type / source matters for the positive excitation because
          ;; all interactions for potential responses are handled through the 
          ;; inhibition parameters. this conditional does have the effect of 
          ;; zeroing out support for competing responses, which takes the place
          ;; of a go/no-go control mechanism to avoid saying the wrong answer.
          ;; once that mechanism is in place, we will need to separate out the 
          ;; support reporting for congruent (zeroed out) and other (pass through)
          ;; cases.
          (* (if (not= response-type (:source x)) 0 1) (:strength x))
          (map #(/ (* (semantic-distance x %) (:strength %)
                      (task-inhibition (:source %) response-type inhibit-conflict?)) 
                   threshold)
               semantic-data)))

(defn- accumulate-support [semantic-elements response-type inhibit-conflict? threshold]
  (loop [sdata (map element-to-semantic-data semantic-elements)
         curr sdata
         m {}]
    (if (empty? curr)
      m
      (recur sdata (rest curr) 
             (update m (:value (first curr)) (fnil + 0) 
                     (semantic-support (first curr) sdata response-type inhibit-conflict? threshold))))))

(defn- update-support [curr new]
  (if (empty? new)
    curr
    (recur (update curr (first (first new)) (fnil + 0) (second (first new))) (rest new))))

(defn- subvocalize
  "Creates a subvocalization request for word"
  [response]
  {:name "subvocalize"
   :arguments {:lexeme response
               :effector :articulator}
   :world nil
   :type "action"})

(defn- conflict-report [component]
  (let [response-value (-> @(:buffer component) :arguments :lexeme)]
    {:name "response-conflict"
     :arguments {:response-category @(:response-type component)
                 :response-value response-value
                 :conflict-values (remove #{response-value} (keys @(:semantic-map component)))}
     :world nil
     :type "instance"}))

(defn- gather-responses [m component]
  (map #(subvocalize (first %))
       (filter #(> (second %) @(:threshold component))
               (into [] m))))

(defrecord ResponseSTM [buffer semantic-map response-type inhibit-conflict? threshold]
  Component
  (receive-focus
    [component focus content]
    (when-let [task-type (d/first-element content :name "update-stroop-response" :type "automation")]
      (reset! (:response-type component) (-> task-type :arguments :task)))

    (when-let [new-parameters (d/first-element content :name "update-response-parameters" :type "automation")]
      (reset! (:threshold component) (-> new-parameters :arguments :threshold))
      (reset! (:inhibit-conflict? component) (-> new-parameters :arguments :conflict?)))

    (let [s (d/filter-elements content :name "semantics")]
     ;; set to 1 to get true and see proper SOA results
;     (reset! (:inhibit-conflict? component) (= (count s) 100))

     ;; this is a mechanism of control. the idea is that when you see one 
     ;; feature of the stimulus and it's not the right one, this reduces 
     ;; the effect of its conflict on the other feature. the  
     ;;
     ;; NOTE 12/6/2021
     ;; conflict at intention level may be the driver for inhibit-conflict? as opposed to 
     ;; direct orders encoded in the intention (i.e., explicit response-type 
     ;; cues). so having word semantics may stimulate a "read the word" intention that
     ;; needs to be suppressed. part of that is to reduce the inhibition from the word
     ;; pathway and the other part of that is to block or heavily inhibit response 
     ;; accumulation from the word pathway.
     ;;
     ;; NOTE 12/8/2021
     ;; we may be able to move this functionality to stroop-control at some point
      (when (and (= (count s) 1)
                 @(:response-type component)
                 (not= (-> s first :arguments :path) @(:response-type component)))
        (reset! (:inhibit-conflict? component) true))
    ;; (reset! (:inhibit-conflict? component) false)

      (swap! (:semantic-map component) update-support
             (into [] (accumulate-support s @(:response-type component) @(:inhibit-conflict? component) @(:threshold component))))
      (display/element-for :display.status "Semantic Layer"
                           (into [] @(:semantic-map component))))

   ;; build a response map from the semantics (unlike semantics, there is no memory at the response level)
   ;; grab responses that pass threshold
    (reset! (:buffer component)
            (gather-responses @(:semantic-map component) component))

   ;; once responses are issued, empty the data structure.
    (when (seq @(:buffer component))
      (when (> (count @(:semantic-map component)) 1)
        (swap! (:buffer component) conj (conflict-report component)))
      (reset! (:semantic-map component) {}))
   ;; there's some bleed over during intertrial activity, so reset this because
   ;; the push button response is supposed to indicate "ready for next trial"
    (when (d/some-element content :name "push-button" :type "environment-action" :action-command :go-button)
     ;; (reset! (:inhibit-conflict? component) false)
      (reset! (:semantic-map component) {})))
  
  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method ResponseSTM [comp ^java.io.Writer w]
  (.write w (format "ResponseSTM{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->ResponseSTM (atom nil) (atom {}) (atom nil) (atom false) (atom (:threshold p)))))