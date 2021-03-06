;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.trace.timer
  (:use
    [potemkin]
    [flatland.useful.datatypes :only (make-record)]
    [lamina.core.utils :only (enqueue enable-unchecked-math)])
  (:require
    [clojure.string :as str]
    [lamina.trace.context :as trace-context]
    [lamina.trace.probe :as p]
    [lamina.core.result :as r]
    [lamina.core.context :as context]
    [lamina.executor.utils :as ex])
  (:import
    [clojure.lang
     ITransientCollection]
    [java.io
     Writer]
    [java.util
     Collections
     HashMap]
    [java.util.concurrent
     ConcurrentLinkedQueue
     ConcurrentHashMap]))

(enable-unchecked-math)

;;;

(definterface+ ITimed
  (timing [_ start])
  (mark-enter [_])
  (mark-error [_ err])
  (mark-waiting [_])
  (mark-return [_ val])
  (add-sub-task [_ timer])
  (add-to-last-sub-task [_ timer])
  (add-to-counter [_ k n]))

(defn dummy-timer [name enqueue-error?]
  (reify
    ITimed
    (timing [_ start]
      {})
    (mark-error [_ err]
      (when enqueue-error?
        (enqueue (p/error-probe-channel [name :error]) {:error err})))
    (mark-enter [_])
    (mark-waiting [_])
    (mark-return [_ _])
    (add-sub-task [_ _] )
    (add-to-last-sub-task [_ _])
    (add-to-counter [_ k n])))

(defn timing-wrapper [t]
  (let [sub-tasks (ConcurrentLinkedQueue.)]
    (reify ITimed
      (timing [_ start]
        (update-in t [:sub-tasks]
          #(map
             (fn [t]
               (if (instance? ITimed t)
                 (timing t start)
                 t))
             (concat % (seq sub-tasks)))))
      (add-sub-task [_ timing]
        (.add ^ConcurrentLinkedQueue sub-tasks timing)))))

(defn add-sub-timing [timing]
  (if-let [timer (context/timer)]
    (add-sub-task timer (timing-wrapper timing))
    false))

(defn add-to-last-sub-timing [timing]
  (if-let [timer (context/timer)]
    (boolean (add-to-last-sub-task timer (timing-wrapper timing)))
    false))

(defn add-to-trace-counter [k n]
  (if-let [timer (context/timer)]
    (do
      (add-to-counter timer k n)
      true)
    false))

(defn increment-trace-counter [k]
  (add-to-trace-counter k 1))

;;;

(defrecord EnqueuedTiming
  [name
   context
   ^long offset
   ^long timestamp
   ^long duration
   ^long enqueued-duration
   ^long compute-duration
   sub-tasks
   args
   result])

(defmacro make-timing [type root-start start & extras]
  `(let [root-start# ~root-start 
         start# ~start
         duration# (if (or
                         (= Long/MIN_VALUE ~'return)
                         (= Long/MIN_VALUE ~'enter))
                     -1
                     (- (long ~'return) (long ~'enter)))
         timing# (make-record ~type
                   :name ~'name
                   :timestamp ~'timestamp
                   :context (trace-context/context)
                   :offset (- (long start#) (long root-start#))
                   :compute-duration (if (= Long/MIN_VALUE ~'waiting)
                                       duration#
                                       (- (long ~'waiting) (long ~'enter)))
                   :duration duration#
                   :args ~'args
                   :result ~'result
                   :sub-tasks (when-not (.isEmpty ~'sub-tasks)
                                (doall (map #(timing % root-start#) ~'sub-tasks)))
                   :counts ~'counts
                   ~@extras)]
     (if-not (neg? ~'start-stage)
       (assoc timing# :start-stage ~'start-stage)
       timing#)))

(deftype+ EnqueuedTimer
  [executor
   capture
   name
   return-probe
   error-probe
   ^long start-stage
   ^long timestamp
   ^long enqueued
   args
   ^{:volatile-mutable true} result
   ^{:volatile-mutable true, :tag long} enter
   ^{:volatile-mutable true, :tag long} waiting
   ^{:volatile-mutable true, :tag long} return
   ^ConcurrentLinkedQueue sub-tasks
   ^HashMap counts]
  ITimed
  (timing [_ start]
    (make-timing EnqueuedTiming start enqueued
      :enqueued-duration (if (= Long/MIN_VALUE enter)
                           -1
                           (- (long enter) (long enqueued)))))
  (add-sub-task [_ timer]
    (.add sub-tasks timer)
    true)
  (add-to-last-sub-task [_ timer]
    (when-let [task (last (seq sub-tasks))]
      (add-sub-task task timer)))
  (add-to-counter [_ k n]
    (locking counts
      (.put counts k (+ (long (or (.get counts k) 0)) (long n)))))
  (mark-enter [this]
    (set! enter (System/nanoTime)))
  (mark-error [this err]
    (set! return (System/nanoTime))
    (let [timing (assoc (timing this enter) :error err)
          probe (or error-probe (p/error-probe-channel [name :error]))]
      (enqueue probe timing)
      (ex/trace-error executor timing)))
  (mark-waiting [this]
    (set! waiting (System/nanoTime)))
  (mark-return [this val]
    (set! return (System/nanoTime))
    (case capture
      (:out :in-out) (set! result val)
      nil)
    (let [timing (make-timing EnqueuedTiming enqueued enqueued
                   :enqueued-duration (if (= Long/MIN_VALUE enter)
                                        -1
                                        (- (long enter) (long enqueued))))]
      (when return-probe
        (enqueue return-probe timing))
      (ex/trace-return executor timing))))

(defn enqueued-timer-
  [executor
   capture
   name
   args
   return-probe
   error-probe
   start-stage
   implicit?
   enqueue-error?]
  (let [enabled? (or
                   (and return-probe (p/probe-enabled? return-probe))
                   (ex/probe-enabled? executor)
                   (and error-probe (p/probe-enabled? error-probe)))
        parent (when (or enabled? implicit?)
                 (context/timer))]
    (if (or enabled? parent)
      (let [timer (EnqueuedTimer.
                    executor
                    capture
                    name
                    (when enabled? return-probe)
                    error-probe
                    start-stage
                    (System/currentTimeMillis)
                    (System/nanoTime)
                    (case capture
                      (:in :in-out) args
                      nil)
                    nil
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    (ConcurrentLinkedQueue.)
                    (HashMap. 0))]
        (when parent
          (add-sub-task parent timer))
        timer)
      (dummy-timer name enqueue-error?))))

(defmacro enqueued-timer
  [executor
   & {:keys [name
             capture
             args
             return-probe
             error-probe
             start-stage
             implicit?
             enqueue-error?]
      :or {implicit? false
           enqueue-error? true
           start-stage -1}}]
  `(enqueued-timer-
     ~executor
     ~capture
     ~name
     ~args
     ~return-probe
     ~error-probe
     ~start-stage
     ~implicit?
     ~enqueue-error?))

;;;

(defrecord Timing
  [name
   context
   ^long timestamp
   ^long offset
   ^long compute-duration
   ^long duration
   sub-tasks
   counts
   args
   result])

(deftype+ Timer
  [capture
   name
   return-probe
   error-probe
   ^long start-stage
   args
   ^{:volatile-mutable true} result
   ^long timestamp
   ^long enter
   ^{:volatile-mutable true, :tag long} waiting
   ^{:volatile-mutable true, :tag long} return
   ^ConcurrentLinkedQueue sub-tasks
   ^HashMap counts]
  ITimed
  (timing [_ start]
    (make-timing Timing start enter))
  (add-sub-task [_ timer]
    (.add sub-tasks timer)
    true)
  (add-to-last-sub-task [_ timer]
    (when-let [task (last (seq sub-tasks))]
      (add-sub-task task timer)))
  (add-to-counter [_ k n]
    (locking counts
      (.put counts k (+ (long (or (.get counts k) 0)) (long n)))))
  (mark-enter [_]
    )
  (mark-waiting [this]
    (set! waiting (System/nanoTime)))
  (mark-error [this err]
    (set! return (System/nanoTime))
    (let [probe (or error-probe (p/error-probe-channel [name :error]))]
      (enqueue probe (assoc (timing this enter) :error err))))
  (mark-return [this val]
    (set! return (System/nanoTime))
    (case capture
      (:out :in-out) (set! result val)
      nil)
    (when return-probe
      (enqueue return-probe
        (make-timing Timing enter enter)))))

(defn timer-
  [capture
   name
   args
   return-probe
   error-probe
   start-stage
   implicit?
   enqueue-error?]
  (let [enabled? (or
                   (and return-probe (p/probe-enabled? return-probe))
                   (and error-probe (p/probe-enabled? error-probe)))
        parent (when (or enabled? implicit?)
                 (context/timer))]
    (if (or enabled? parent)
      (let [timer (Timer.
                    capture
                    name
                    (when enabled? return-probe)
                    error-probe
                    start-stage
                    (case capture
                      (:in :in-out) args
                      nil)
                    nil
                    (System/currentTimeMillis)
                    (System/nanoTime)
                    Long/MIN_VALUE
                    Long/MIN_VALUE
                    (ConcurrentLinkedQueue.)
                    (HashMap. 0))]
        (when parent
          (add-sub-task parent timer))
        timer)
      (dummy-timer name enqueue-error?))))

(defmacro timer
  [& {:keys [name
             capture
             args
             return-probe
             error-probe
             start-stage
             implicit?
             enqueue-error?]
      :or {implicit? false
           enqueue-error? true
           start-stage -1}
      :as options}]
  `(timer-
     ~capture
     ~name
     ~args
     ~return-probe
     ~error-probe
     ~start-stage
     ~implicit?
     ~enqueue-error?))

;;;

(defn indent [n s]
  (->> s
    str/split-lines
    (map #(str "  " %))
    (interpose "\n")
    (apply str)))

(def durations
  {"ns" 1
   "us" 1e3
   "ms" 1e6
   "s" 1e9})

(defn duration [n scale]
  (str
    (format "%.1f" (float (/ n (durations scale))))
    scale))

(defn format-duration [n]
  (cond
    (< n 1e3) (duration n "ns")
    (< n 1e6) (duration n "us")
    (< n 1e9) (duration n "ms")
    :else (duration n "s")))

(defn format-timing [t]
  (let [desc (:name t)
        desc (if (instance? clojure.lang.Named desc)
               (name desc)
               (str desc))
        duration (:duration t)
        compute (:compute-duration t)
        enqueued (:enqueued-duration t)]
    (str/trim
      (str desc
        " - "
        (if duration
          (format-duration (+ (or enqueued 0) duration))
          "incomplete")
        (when (or enqueued (not= compute duration))
          " - ")
        (when enqueued
          (str "enqueued for " (format-duration enqueued)))
        (when (not= compute duration)
          (str
            (when enqueued ", ")
            "computed for " (format-duration compute)
            (when duration
              (str ", waited for " (format-duration (- duration compute))))))
        "\n"
        (indent 2
          (->> t
            :sub-tasks
            (map format-timing)
            (interpose "\n")
            (apply str)))))))

;;;

(definterface+ IDistilledTiming
  (add-sub-timing! [_ x])
  (set-context-visible! [_ visible?]))

(declare distilled-timing)

(def-map-type DistilledTiming
  [task-name
   durations ;; todo: make this some sort of primitive collection
   ^HashMap sub-tasks
   ^HashMap counts
   context
   ^{:volatile-mutable true} context-visible?]

  ;; map impl
  (get* [_ k default]
    (case k
      :task task-name
      :durations @durations
      :sub-tasks (or (seq (.values sub-tasks)) default)
      :counts counts
      :context (if context-visible? context default)
      default))
  (keys* [_]
    (concat
      [:task :durations]
      (when context-visible?
        [:context])
      (when-not (.isEmpty sub-tasks)
        [:sub-tasks])
      (when-not (.isEmpty counts)
        [:counts])))
  (assoc* [this k v]
    (assoc (into {} this) k v))
  (dissoc* [this k]
    (dissoc (into {} this) k))

  IDistilledTiming
  (add-sub-timing! [_ timing]
    (let [^DistilledTiming timing timing]

      ;; merge sub-tasks
      (let [k [(:task timing) (:context timing)]]
        (if-let [sub-timing (get sub-tasks k)]
          
          ;; already registered, just merge this in
          (conj! sub-timing timing)
          
          ;; create and put, allowing for race conditions
          (let [sub-timing (distilled-timing (.task-name timing) (.context timing))]
            (when (= (.context timing) context)
              (set-context-visible! sub-timing false))
            (.put sub-tasks k sub-timing)
            (conj! sub-timing timing))))))

  (set-context-visible! [_ visible?]
    (set! context-visible? visible?))

  ITransientCollection
  (conj [this timing]
    (swap! durations #(concat %2 %1) (:durations timing))

    ;; merge counts
    (doseq [[k v] (:counts timing)]
      (.put counts k (+ (long (or (.get counts k) 0)) (long v))))
    
    (doseq [t (:sub-tasks timing)]
      (add-sub-timing! this t))

    this))

(defn distilled-timing
  ([name context]
     (distilled-timing name context nil nil))
  ([name context counts durations]
     (DistilledTiming.
       name
       (atom (vec durations))
       (HashMap. 2)
       (if counts
         (HashMap. ^HashMap counts)
         (HashMap. 0))
       context
       true)))

(defn distill-timing
  "Returns a distillation of the timing object, containing only :task, :durations, :context, and :sub-tasks.
   This is an idempotent operation.

   This data structure can be merged using merge-distilled-timings."
  [timing]
  (if (instance? DistilledTiming timing)
    timing
    (let [^DistilledTiming timing* (distilled-timing
                                     (or (:task timing) (:name timing))
                                     (:context timing)
                                     (:counts timing)
                                     (or (:durations timing)
                                       (if-let [d (:duration timing)]
                                         [d]
                                         [])))]
      (doseq [t (:sub-tasks timing)]
        (add-sub-timing! timing* (distill-timing t)))
      timing*)))

(defn merge-distilled-timings
  "Returns a list of one or distilled timings, where :durations have been concatenated together for
   identical tasks."
  [& distilled-timings]
  (when-not (empty? distilled-timings)
    (let [root-timing (distilled-timing nil nil)]
      (doseq [t distilled-timings]
        (add-sub-timing! root-timing t))
      (:sub-tasks root-timing))))

;;;

(defmethod print-method DistilledTiming [o ^Writer w]
  (.write w (pr-str (into {} o))))

(defmethod print-method Timing [o ^Writer w]
  (.write w (pr-str (into {} o))))

(defmethod print-method EnqueuedTiming [o ^Writer w]
  (.write w (pr-str (into {} o))))
