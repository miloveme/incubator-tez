/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.tez.mapreduce.hadoop;

import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEvent;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEventStatus;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.tez.common.TezTaskStatus.Phase;
import org.apache.tez.common.counters.CounterGroup;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.records.TezDependentTaskCompletionEvent;
import org.apache.tez.records.TezTaskAttemptID;

public class TezTypeConverters {

  // Tez objects will be imported. Others will use the fully qualified name when
  // required.
  // All public methods named toYarn / toTez / toMapReduce

  public static org.apache.hadoop.mapreduce.v2.api.records.Phase toYarn(
      Phase phase) {
    return org.apache.hadoop.mapreduce.v2.api.records.Phase.valueOf(phase
        .name());
  }

  public static TaskAttemptId toYarn(TezTaskAttemptID taskAttemptId) {
    TaskAttemptID mrTaskAttemptId = IDConverter
        .toMRTaskAttemptId(taskAttemptId);
    TaskAttemptId mrv2TaskAttemptId = TypeConverter.toYarn(mrTaskAttemptId);
    return mrv2TaskAttemptId;
  }

  public static TezTaskAttemptID toTez(TaskAttemptId taskAttemptId) {
    TaskAttemptID mrTaskAttemptId = TypeConverter.fromYarn(taskAttemptId);
    TezTaskAttemptID tezTaskAttemptId = IDConverter
        .fromMRTaskAttemptId(mrTaskAttemptId);
    return tezTaskAttemptId;
  }

  public static TezDependentTaskCompletionEvent.Status toTez(
      TaskAttemptCompletionEventStatus status) {
    return TezDependentTaskCompletionEvent.Status.valueOf(status.toString());
  }

  public static TezDependentTaskCompletionEvent toTez(
      TaskAttemptCompletionEvent event) {
    return new TezDependentTaskCompletionEvent(event.getEventId(),
        toTez(event.getAttemptId()), event.getAttemptId().getTaskId()
            .getTaskType() == TaskType.MAP, toTez(event.getStatus()),
        event.getMapOutputServerAddress());
  }
  
  public static Counters fromTez(TezCounters tezCounters) {
    if (tezCounters == null) {
      return null;
    }
    Counters counters = new Counters();
    for (CounterGroup xGrp : tezCounters) {
      counters.addGroup(xGrp.getName(), xGrp.getDisplayName());
      for (TezCounter xCounter : xGrp) {
        Counter counter =
            counters.findCounter(xGrp.getName(), xCounter.getName());
        counter.setValue(xCounter.getValue());

      }
    }
    return counters;
  }
}