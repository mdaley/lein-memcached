(ns leiningen.memcached
  (:require [leiningen.core.main :as main])
  (:import [com.thimbleware.jmemcached MemCacheDaemon CacheImpl]
           [com.thimbleware.jmemcached.storage CacheStorage]
           [com.thimbleware.jmemcached.storage.hash ConcurrentLinkedHashMap
                                                    ConcurrentLinkedHashMap$EvictionPolicy]
           [java.net InetSocketAddress]))

(defn- config-value
  "Get a value from project config or, optionally, use a default value."
  [project k & [default]]
  (get (project :memcached) k default))

(defn- create-store
  "Create a store for memcached to use."
  [max-items max-bytes]
  (ConcurrentLinkedHashMap/create ConcurrentLinkedHashMap$EvictionPolicy/FIFO max-items max-bytes))

(defn- create-instance
  "Create an instance of the memcached daemon."
  [port max-items max-bytes verbose binary]
  (doto (MemCacheDaemon.)
    (.setCache (CacheImpl. (create-store max-items max-bytes)))
    (.setAddr (InetSocketAddress. port))
    (.setVerbose verbose)
    (.setBinary binary)))

(defn memcached
  "Run memcached in-memory."
  [project & args]
  (let [port (config-value project :port 11211)
        max-items (config-value project :max-items 100)
        max-bytes (config-value project :max-bytes 100000)
        verbose (config-value project :verbose false)
        binary (config-value project :binary false)]
    (println (str "lein-memcached: starting in-memory memcached instance on port " port "."))
    (let [mc-server (create-instance port max-items max-bytes verbose binary)]
      (.start mc-server)
      (if (seq args)
        (try
          (main/apply-task (first args) project (rest args))
          (finally (.stop mc-server)))
        (while true (Thread/sleep 5000))))))
