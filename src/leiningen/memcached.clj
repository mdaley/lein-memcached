(ns leiningen.memcached
  (:require [leiningen.daemon :as daemon]
            [leiningen.core.main :as main])
  (:import [com.thimbleware.jmemcached
            CacheImpl]
           [com.thimbleware.jmemcached.storage.hash ConcurrentLinkedHashMap
            ConcurrentLinkedHashMap$EvictionPolicy]
           [java.net InetSocketAddress]))

(def running (ref false))

(defn- config-value
  "Get a value from project config or, optionally, use a default value."
  [project k & [default]]
  (get (project :memcached) k default))

(defn- create-store
  "Create a store for memcached to use."
  [max-items max-bytes]
  (ConcurrentLinkedHashMap/create ConcurrentLinkedHashMap$EvictionPolicy/FIFO max-items max-bytes))

(def cache-daemon
  (daemon/create-daemon))

(defn- create-instance
  "Create an instance of the memcached daemon."
  [port max-items max-bytes verbose binary]
  (doto cache-daemon
    (.setCache (CacheImpl. (create-store max-items max-bytes)))
    (.setAddr (InetSocketAddress. port))
    (.setVerbose verbose)
    (.setBinary binary)))

(defn start-memcached
  [project]
  (if (not @running)
    (dosync
     (let [port (config-value project :port 11211)
           max-items (config-value project :max-items 100)
           max-bytes (config-value project :max-bytes 100000)
           verbose (config-value project :verbose false)
           binary (config-value project :binary false)]
       (println (str "lein-memcached: starting in-memory memcached instance on port " port "."))
       (.start (create-instance port max-items max-bytes verbose binary))
       (ref-set running true)))
    (throw (RuntimeException. "Already running"))))

(defn stop-memcached
  []
  (dosync
   (.stop cache-daemon)
   (ref-set running false)))

(defn memcached
  "Run memcached in-memory."
  [project & args]
  (start-memcached project)
  (if (seq args)
    (try
      (main/apply-task (first args) project (rest args))
      (finally (.stop cache-daemon)))
    (while running (Thread/sleep 500))))
