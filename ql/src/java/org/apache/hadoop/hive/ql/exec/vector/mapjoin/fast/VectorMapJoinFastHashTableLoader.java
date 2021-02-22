/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.vector.mapjoin.fast;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


import org.apache.hadoop.hive.llap.LlapDaemonInfo;
import org.apache.hadoop.hive.ql.exec.MemoryMonitorInfo;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mapjoin.MapJoinMemoryExhaustionError;
import org.apache.tez.common.counters.TezCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.MapJoinOperator;
import org.apache.hadoop.hive.ql.exec.MapredContext;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapperContext;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainer;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainerSerDe;
import org.apache.hadoop.hive.ql.exec.tez.TezContext;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.MapJoinDesc;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.io.BytesWritable;
import org.apache.tez.runtime.api.Input;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.library.api.KeyValueReader;
import org.apache.tez.runtime.api.AbstractLogicalInput;

/**
 * HashTableLoader for Tez constructs the hashtable from records read from
 * a broadcast edge.
 */
public class VectorMapJoinFastHashTableLoader implements org.apache.hadoop.hive.ql.exec.HashTableLoader {

  private static final Logger LOG = LoggerFactory.getLogger(VectorMapJoinFastHashTableLoader.class.getName());

  public static final int HASHTABLE_EXECUTOR_THREADS = 4;

  public static class HashTableElement {
    byte[] keyBytes;
    int keyLength;
    byte[] valueBytes;
    int valueLength;
    long deserializeKey;
    long hashCode;

    public HashTableElement(byte[] keyBytes, int keyLength, byte[] valueBytes, int valueLength, long key, long hashCode) {
      this.keyBytes = keyBytes;
      this.keyLength = keyLength;
      this.valueBytes = valueBytes;
      this.valueLength = valueLength;
      this.deserializeKey = key;
      this.hashCode = hashCode;
    }

    public BytesWritable getKey() {
      return new BytesWritable(keyBytes, keyLength);
    }

    public BytesWritable getValue() {
      return new BytesWritable(valueBytes, valueLength);
    }

    public long getDeserializeKey() {
      return deserializeKey;
    }

    public long getHashCode() {
      return hashCode;
    }
  }

  public static class QueueElementBatch {
    HashTableElement[] batch;
    /*byte[] bytes;
    int[] lengths;
    long[] deserializeKeys;
    long[] hashCodes;*/
    int currentIndex;

    public QueueElementBatch() {
      batch = new HashTableElement[1024];
      /*bytes = new byte[4 * 1024 * 1024];
      lengths = new int[2 * 1024];
      deserializeKeys = new long[1024];
      hashCodes = new long[1024];*/
      currentIndex = 0;
    }

    public boolean addElement(HashTableElement h) {
      batch[currentIndex] = h;
      currentIndex++;
      return (currentIndex == 1024);
    }

    /*public void addElement(byte[] keyByte, int keyLength, byte[] valueByte, int valueLength, long deserializeKey,
        long hashCode) {
      int start = 0;
      if (currentIndex > 0) {
        start = lengths[(currentIndex * 2) - 1];
      }
      if (keyByte != null) {
        // use System.Arraycopy
        for (int i = 0; i < keyLength; ++i) {
          bytes[start + i] = keyByte[i];
        }
      }
      if (valueByte != null) {
        // use System.Arraycopy
        for (int i = 0; i < valueLength; ++i) {
          bytes[start + keyLength + i] = valueByte[i];
        }
      }
      //change to end offsets
      lengths[currentIndex * 2] = start + keyLength;
      lengths[(currentIndex * 2) + 1] = start + keyLength + valueLength;
      deserializeKeys[currentIndex] = deserializeKey;
      hashCodes[currentIndex] = hashCode;
      currentIndex++;
    }

    public boolean canAdd(int keyLength, int valueLength) {
      if (currentIndex >= 1024) {
        return false;
      }
      int start = 0;
      if (currentIndex > 0) {
        start = lengths[(currentIndex * 2) - 1];
      }
      if ((start + keyLength + valueLength) < bytes.length) {
        return true;
      }
      return false;
    }*/

    public HashTableElement getBatch(int i) {
      return batch[i];
      /*int start = 0;
      if (i > 0) {
        start = lengths[(i * 2) - 1];
      }
      LOG.info("For i: "+i+" key start: "+start+" key end: "+lengths[i * 2]+
          " value start: "+lengths[i * 2]+" value end: "+lengths[(i * 2) + 1]);
      return new HashTableElement(Arrays.copyOfRange(bytes, start, lengths[i * 2]),
          lengths[i * 2] - start,
          Arrays.copyOfRange(bytes, lengths[i * 2], lengths[(i * 2) + 1]),
          lengths[(i * 2) + 1] - lengths[i * 2],
          deserializeKeys[i],
          hashCodes[i]);*/
    }

    public int getSize() {
      return currentIndex;
    }
  }

  private Configuration hconf;
  protected MapJoinDesc desc;
  private TezContext tezContext;
  private String cacheKey;
  private TezCounter htLoadCounter;
  private long numEntries;
  private AtomicLong totalEntries;
  private static final QueueElementBatch sentinel = new QueueElementBatch();

  @Override
  public void init(ExecMapperContext context, MapredContext mrContext,
      Configuration hconf, MapJoinOperator joinOp) {
    this.tezContext = (TezContext) mrContext;
    this.hconf = hconf;
    this.desc = joinOp.getConf();
    this.cacheKey = joinOp.getCacheKey();
    String counterGroup = HiveConf.getVar(hconf, HiveConf.ConfVars.HIVECOUNTERGROUP);
    String vertexName = hconf.get(Operator.CONTEXT_NAME_KEY, "");
    String counterName = Utilities.getVertexCounterName(HashTableLoaderCounters.HASHTABLE_LOAD_TIME_MS.name(), vertexName);
    this.htLoadCounter = tezContext.getTezProcessorContext().getCounters().findCounter(counterGroup, counterName);
    this.numEntries = 0;
    totalEntries = new AtomicLong(0);
  }

  public void drainQueueAndLoad(VectorMapJoinFastTableContainer vectorMapJoinFastTableContainer, boolean doMemCheck,
      String inputName, MemoryMonitorInfo memoryMonitorInfo, long effectiveThreshold, int partitionId,
      BlockingQueue<QueueElementBatch>[] sharedQ)
      throws InterruptedException, IOException, HiveException, SerDeException {
    LOG.info("Draining thread " + partitionId + " started");
    long entries = 0;
    BlockingQueue<QueueElementBatch>[] partitionQ = sharedQ;
    while(true) {
      QueueElementBatch batch = partitionQ[partitionId].take();
      if(batch == sentinel) {
        break;
      }
      LOG.info("Draining thread " + partitionId + " got size "+ batch.getSize());
      for (int i = 0; i < batch.getSize(); i++) {
        try {
          HashTableElement h = batch.getBatch(i);
          vectorMapJoinFastTableContainer
              .putRow(h.getKey(), h.getValue(), h.getHashCode(), h.getDeserializeKey());
        }
        catch(Exception e) {
          LOG.info("Exception in draining thread put row: ",e);
          throw new HiveException(e);
        }
        ++entries;
      }
      LOG.info("Draining thread added entries : "+ entries);
      totalEntries.set(totalEntries.get() + entries);
      entries = 0;
      long hashTableEntries = totalEntries.get();
      if (doMemCheck && (hashTableEntries % memoryMonitorInfo.getMemoryCheckInterval() == 0)) {
        final long estMemUsage = vectorMapJoinFastTableContainer.getEstimatedMemorySize();
        if (estMemUsage > effectiveThreshold) {
          String msg = "Hash table loading exceeded memory limits for input: " + inputName +
              " numEntries: " + hashTableEntries + " estimatedMemoryUsage: " + estMemUsage +
              " effectiveThreshold: " + effectiveThreshold + " memoryMonitorInfo: " + memoryMonitorInfo;
          LOG.error(msg);
          throw new MapJoinMemoryExhaustionError(msg);
        } else {
          if (LOG.isInfoEnabled()) {
            LOG.info("Checking hash table loader memory usage for input: {} numEntries: {} " +
                    "estimatedMemoryUsage: {} effectiveThreshold: {}", inputName, hashTableEntries, estMemUsage,
                effectiveThreshold);
          }
        }
      }
    }
    LOG.info("Draining thread "+ partitionId +" is finished");
  }

  public void drain(VectorMapJoinFastTableContainer vectorMapJoinFastTableContainer, boolean doMemCheck,
      String inputName, MemoryMonitorInfo memoryMonitorInfo, long effectiveThreshold, ExecutorService executorService,
      BlockingQueue<QueueElementBatch>[] sharedQ)
      throws InterruptedException, IOException, HiveException, SerDeException {
    boolean finalDoMemCheck = doMemCheck;
    long finalEffectiveThreshold = effectiveThreshold;
    for (int partitionId = 0; partitionId < HASHTABLE_EXECUTOR_THREADS; ++partitionId) {
      int finalPartitionId = partitionId;
      executorService.submit(() -> {
        try {
          LOG.info("Partition id is: "+ finalPartitionId + " Queue size is: "+ sharedQ[finalPartitionId].size());
          drainQueueAndLoad(vectorMapJoinFastTableContainer, finalDoMemCheck, inputName, memoryMonitorInfo,
              finalEffectiveThreshold, finalPartitionId, sharedQ);
        } catch (HiveException e) {
          e.printStackTrace();
        } catch (IOException | InterruptedException | SerDeException e) {
          e.printStackTrace();
        }
      });
    }
  }

  @Override
  public void load(MapJoinTableContainer[] mapJoinTables,
      MapJoinTableContainerSerDe[] mapJoinTableSerdes)
      throws HiveException {

    Map<Integer, String> parentToInput = desc.getParentToInput();
    Map<Integer, Long> parentKeyCounts = desc.getParentKeyCounts();

    MemoryMonitorInfo memoryMonitorInfo = desc.getMemoryMonitorInfo();
    boolean doMemCheck = false;
    long effectiveThreshold = 0;
    if (memoryMonitorInfo != null) {
      effectiveThreshold = memoryMonitorInfo.getEffectiveThreshold(desc.getMaxMemoryAvailable());

      // hash table loading happens in server side, LlapDecider could kick out some fragments to run outside of LLAP.
      // Flip the flag at runtime in case if we are running outside of LLAP
      if (!LlapDaemonInfo.INSTANCE.isLlap()) {
        memoryMonitorInfo.setLlap(false);
      }
      if (memoryMonitorInfo.doMemoryMonitoring()) {
        doMemCheck = true;
        if (LOG.isInfoEnabled()) {
          LOG.info("Memory monitoring for hash table loader enabled. {}", memoryMonitorInfo);
        }
      }
    }

    if (!doMemCheck) {
      if (LOG.isInfoEnabled()) {
        LOG.info("Not doing hash table memory monitoring. {}", memoryMonitorInfo);
      }
    }
    for (int pos = 0; pos < mapJoinTables.length; pos++) {
      if (pos == desc.getPosBigTable()) {
        continue;
      }

      String inputName = parentToInput.get(pos);
      LogicalInput input = tezContext.getInput(inputName);

      try {
        input.start();
        tezContext.getTezProcessorContext().waitForAnyInputReady(
            Collections.<Input> singletonList(input));
      } catch (Exception e) {
        throw new HiveException(e);
      }

      try {
        KeyValueReader kvReader = (KeyValueReader) input.getReader();

        Long keyCountObj = parentKeyCounts.get(pos);
        long estKeyCount = (keyCountObj == null) ? -1 : keyCountObj;

        long inputRecords = -1;
        try {
          //TODO : Need to use class instead of string.
          // https://issues.apache.org/jira/browse/HIVE-23981
          inputRecords = ((AbstractLogicalInput) input).getContext().getCounters().
                  findCounter("org.apache.tez.common.counters.TaskCounter",
                          "APPROXIMATE_INPUT_RECORDS").getValue();
        } catch (Exception e) {
          LOG.debug("Failed to get value for counter APPROXIMATE_INPUT_RECORDS", e);
        }
        long keyCount = Math.max(estKeyCount, inputRecords);

        VectorMapJoinFastTableContainer vectorMapJoinFastTableContainer =
                new VectorMapJoinFastTableContainer(desc, hconf, keyCount);

        LOG.info("Loading hash table for input: {} cacheKey: {} tableContainer: {} smallTablePos: {} " +
                "estKeyCount : {} keyCount : {}", inputName, cacheKey,
                vectorMapJoinFastTableContainer.getClass().getSimpleName(), pos, estKeyCount, keyCount);

        vectorMapJoinFastTableContainer.setSerde(null, null); // No SerDes here.
        ExecutorService executorService = Executors.newFixedThreadPool(HASHTABLE_EXECUTOR_THREADS);
        boolean drained = false;
        BlockingQueue<QueueElementBatch>[] sharedQ = new BlockingQueue[HASHTABLE_EXECUTOR_THREADS];
        for(int i = 0; i < HASHTABLE_EXECUTOR_THREADS; ++i) {
          sharedQ[i] = new LinkedBlockingQueue<>();
        }
        QueueElementBatch[] batches = new QueueElementBatch[HASHTABLE_EXECUTOR_THREADS];
        for (int i = 0; i < HASHTABLE_EXECUTOR_THREADS; ++i) {
          batches[i] = new QueueElementBatch();
        }
        //start the threads
        drain(vectorMapJoinFastTableContainer, doMemCheck, inputName, memoryMonitorInfo,
            effectiveThreshold, executorService, sharedQ);
        long startTime = System.currentTimeMillis();
        while (kvReader.next()) {
          BytesWritable currentKey = (BytesWritable) kvReader.getCurrentKey();
          BytesWritable currentValue = (BytesWritable) kvReader.getCurrentValue();
          long key = vectorMapJoinFastTableContainer.deserializeToKey(currentKey);
          long hashCode = vectorMapJoinFastTableContainer.calculateLongHashCode(key, currentKey);
          int partitionId = (int) (((1 << 2) - 1) & hashCode);
          numEntries++;
          // call getBytes as copy is called later
          byte[] valueBytes = currentValue.copyBytes();
          int valueLength = currentValue.getLength();
          byte[] keyBytes = currentKey.copyBytes();
          int keyLength = currentKey.getLength();
          HashTableElement h = new HashTableElement(keyBytes, keyLength, valueBytes, valueLength, key, hashCode);
          if (batches[partitionId].addElement(h)) {
              sharedQ[partitionId].add(batches[partitionId]);
              batches[partitionId] = new QueueElementBatch();
          }
          /*if (!batches[partitionId].canAdd(keyLength, valueLength)) {
             sharedQ[partitionId].add(batches[partitionId]);
             batches[partitionId] = new QueueElementBatch();
           }
           LOG.info("For numEntries: "+numEntries+" keybytes length : "+keyBytes.length+" keyLength: "+keyLength+
               " valueBytes length: "+valueBytes.length+" valueLength "+valueLength);
           batches[partitionId].addElement(keyBytes, keyLength, valueBytes, valueLength, key, hashCode);*/
        }

        LOG.info("Finished loading the queue for input: {} endTime : {}", inputName, System.currentTimeMillis());

        // Add sentinel at the end of queue
        for (int i=0; i<4; ++i) {
          // add sentinel to the q not the batch
          //batches[i].addElement(sentinel.keyBytes, -1, sentinel.valueBytes, -1,
          //    sentinel.getDeserializeKey(), sentinel.getHashCode());
          sharedQ[i].add(batches[i]);
          sharedQ[i].add(sentinel);
        }

        executorService.shutdown();
        try {
          executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        LOG.info("Total entries added to queue after looping: "+numEntries);
        LOG.info("Total entries added to hash table: "+ totalEntries.get());

        long delta = System.currentTimeMillis() - startTime;
        htLoadCounter.increment(delta);

        vectorMapJoinFastTableContainer.seal();
        mapJoinTables[pos] = vectorMapJoinFastTableContainer;
        if (doMemCheck) {
          LOG.info("Finished loading hash table for input: {} cacheKey: {} numEntries: {} " +
              "estimatedMemoryUsage: {} Load Time : {} ", inputName, cacheKey, numEntries,
            vectorMapJoinFastTableContainer.getEstimatedMemorySize(), delta);
        } else {
          LOG.info("Finished loading hash table for input: {} cacheKey: {} numEntries: {} Load Time : {} ",
                  inputName, cacheKey, numEntries, delta);
        }
      } catch (IOException e) {
        throw new HiveException(e);
      } catch (SerDeException e) {
        throw new HiveException(e);
      } catch (Exception e) {
        throw new HiveException(e);
      }
    }
  }
}
