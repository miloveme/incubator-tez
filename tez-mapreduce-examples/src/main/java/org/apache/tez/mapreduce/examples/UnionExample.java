/**
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
package org.apache.tez.mapreduce.examples;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.split.TezGroupedSplitsInputFormat;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.tez.client.AMConfiguration;
import org.apache.tez.client.TezClient;
import org.apache.tez.client.TezClientUtils;
import org.apache.tez.client.TezSession;
import org.apache.tez.client.TezSessionConfiguration;
import org.apache.tez.client.TezSessionStatus;
import org.apache.tez.dag.api.GroupInputEdge;
import org.apache.tez.dag.api.VertexGroup;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.OutputDescriptor;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.EdgeProperty.DataMovementType;
import org.apache.tez.dag.api.EdgeProperty.DataSourceType;
import org.apache.tez.dag.api.EdgeProperty.SchedulingType;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.dag.api.client.StatusGetOpts;
import org.apache.tez.mapreduce.committer.MROutputCommitter;
import org.apache.tez.mapreduce.common.MRInputAMSplitGenerator;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.hadoop.MultiStageMRConfToTezTranslator;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.LogicalIOProcessor;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.api.LogicalOutput;
import org.apache.tez.runtime.api.TezProcessorContext;
import org.apache.tez.runtime.api.TezRootInputInitializer;
import org.apache.tez.runtime.library.api.KeyValueReader;
import org.apache.tez.runtime.library.api.KeyValueWriter;
import org.apache.tez.runtime.library.api.KeyValuesReader;
import org.apache.tez.runtime.library.input.ConcatenatedMergedKeyValuesInput;
import org.apache.tez.runtime.library.input.ShuffledMergedInput;
import org.apache.tez.runtime.library.output.OnFileSortedOutput;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

public class UnionExample {

  public static class TokenProcessor implements LogicalIOProcessor {
    TezProcessorContext context;
    IntWritable one = new IntWritable(1);
    Text word = new Text();

    @Override
    public void initialize(TezProcessorContext processorContext)
        throws Exception {
      this.context = processorContext;
    }

    @Override
    public void handleEvents(List<Event> processorEvents) {
    }

    @Override
    public void close() throws Exception {
    }

    @Override
    public void run(Map<String, LogicalInput> inputs,
        Map<String, LogicalOutput> outputs) throws Exception {
      Preconditions.checkArgument(inputs.size() == 1);
      for (LogicalInput input : inputs.values()) {
        input.start();
      }
      for (LogicalOutput output : outputs.values()) {
        output.start();
      }
      boolean inUnion = true;
      if (context.getTaskVertexName().equals("map3")) {
        inUnion = false;
      }
      Preconditions.checkArgument(outputs.size() == (inUnion ? 2 : 1));
      Preconditions.checkArgument(outputs.containsKey("checker"));
      MRInput input = (MRInput) inputs.values().iterator().next();
      KeyValueReader kvReader = input.getReader();
      OnFileSortedOutput output = (OnFileSortedOutput) outputs.get("checker");
      KeyValueWriter kvWriter = output.getWriter();
      MROutput parts = null;
      KeyValueWriter partsWriter = null;
      if (inUnion) {
        parts = (MROutput) outputs.get("parts");
        partsWriter = parts.getWriter();
      }
      while (kvReader.next()) {
        StringTokenizer itr = new StringTokenizer(kvReader.getCurrentValue().toString());
        while (itr.hasMoreTokens()) {
          word.set(itr.nextToken());
          kvWriter.write(word, one);
          if (inUnion) {
            partsWriter.write(word, one);
          }
        }
      }
      if (inUnion) {
        if (parts.isCommitRequired()) {
          while (!context.canCommit()) {
            Thread.sleep(100);
          }
          parts.commit();
        }
      }
    }
    
  }
  
  public static class UnionProcessor implements LogicalIOProcessor {
    TezProcessorContext context;
    IntWritable one = new IntWritable(1);
    
    @Override
    public void initialize(TezProcessorContext processorContext)
        throws Exception {
      this.context = processorContext;
    }

    @Override
    public void handleEvents(List<Event> processorEvents) {
    }

    @Override
    public void close() throws Exception {
    }

    @Override
    public void run(Map<String, LogicalInput> inputs,
        Map<String, LogicalOutput> outputs) throws Exception {
      Preconditions.checkArgument(inputs.size() == 2);
      Preconditions.checkArgument(outputs.size() == 2);
      for (LogicalInput input : inputs.values()) {
        input.start();
      }
      for (LogicalOutput output : outputs.values()) {
        output.start();
      }
      MROutput out = (MROutput) outputs.get("union");
      MROutput allParts = (MROutput) outputs.get("all-parts");
      KeyValueWriter kvWriter = out.getWriter();
      KeyValueWriter partsWriter = allParts.getWriter();
      Map<String, AtomicInteger> unionKv = Maps.newHashMap();
      LogicalInput union = inputs.get("union");
      KeyValuesReader kvReader = (KeyValuesReader) union.getReader();
      while (kvReader.next()) {
        String word = ((Text) kvReader.getCurrentKey()).toString();
        IntWritable intVal = (IntWritable) kvReader.getCurrentValues().iterator().next();
        for (int i=0; i<intVal.get(); ++i) {
          partsWriter.write(word, one);
        }
        AtomicInteger value = unionKv.get(word);
        if (value == null) {
          unionKv.put(word, new AtomicInteger(intVal.get()));
        } else {
          value.addAndGet(intVal.get());
        }
      }
      LogicalInput map3 = inputs.get("map3");
      kvReader = (KeyValuesReader) map3.getReader();
      while (kvReader.next()) {
        String word = ((Text) kvReader.getCurrentKey()).toString();
        IntWritable intVal = (IntWritable) kvReader.getCurrentValues().iterator().next();
        AtomicInteger value = unionKv.get(word);
        if  (value == null) {
          throw new TezUncheckedException("Expected to exist: " + word);
        } else {
          value.getAndAdd(intVal.get()*-2);
        }
      }
      for (AtomicInteger value : unionKv.values()) {
        if (value.get() != 0) {
          throw new TezUncheckedException("Unexpected non-zero value");
        }
      }
      kvWriter.write("Union", new IntWritable(unionKv.size()));
      if (out.isCommitRequired()) {
        while (!context.canCommit()) {
          Thread.sleep(100);
        }
        out.commit();
      }
      if (allParts.isCommitRequired()) {
        while (!context.canCommit()) {
          Thread.sleep(100);
        }
        allParts.commit();
      }
    }
    
  }
  
  private DAG createDAG(FileSystem fs, TezConfiguration tezConf,
      Map<String, LocalResource> localResources, Path stagingDir,
      String inputPath, String outputPath) throws IOException {
    Configuration mapStageConf = new JobConf((Configuration)tezConf);
    mapStageConf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS,
        Text.class.getName());
    mapStageConf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS,
        IntWritable.class.getName());
    mapStageConf.set(MRJobConfig.INPUT_FORMAT_CLASS_ATTR, 
        TezGroupedSplitsInputFormat.class.getName());

    mapStageConf.set(FileInputFormat.INPUT_DIR, inputPath);
    mapStageConf.setBoolean("mapred.mapper.new-api", true);

    MultiStageMRConfToTezTranslator.translateVertexConfToTez(mapStageConf,
        null);

    Configuration finalReduceConf = new JobConf((Configuration)tezConf);
    finalReduceConf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS,
        Text.class.getName());
    finalReduceConf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS,
        IntWritable.class.getName());
    finalReduceConf.set(MRJobConfig.OUTPUT_FORMAT_CLASS_ATTR,
        TextOutputFormat.class.getName());
    finalReduceConf.set(FileOutputFormat.OUTDIR, outputPath);
    finalReduceConf.setBoolean("mapred.mapper.new-api", true);

    MultiStageMRConfToTezTranslator.translateVertexConfToTez(finalReduceConf,
        mapStageConf);

    MRHelpers.doJobClientMagic(mapStageConf);
    MRHelpers.doJobClientMagic(finalReduceConf);

    byte[] mapPayload = MRHelpers.createUserPayloadFromConf(mapStageConf);
    byte[] mapInputPayload = MRHelpers.createMRInputPayloadWithGrouping(mapPayload, 
            TextInputFormat.class.getName());
    int numMaps = -1;
    Vertex mapVertex1 = new Vertex("map1", new ProcessorDescriptor(
        TokenProcessor.class.getName()),
        numMaps, MRHelpers.getMapResource(mapStageConf));
    mapVertex1.setJavaOpts(MRHelpers.getMapJavaOpts(mapStageConf));
    Map<String, String> mapEnv = new HashMap<String, String>();
    MRHelpers.updateEnvironmentForMRTasks(mapStageConf, mapEnv, true);
    mapVertex1.setTaskEnvironment(mapEnv);
    Class<? extends TezRootInputInitializer> initializerClazz = MRInputAMSplitGenerator.class;
    InputDescriptor id = new InputDescriptor(MRInput.class.getName()).
        setUserPayload(mapInputPayload);
    mapVertex1.addInput("MRInput", id, initializerClazz);

    Vertex mapVertex2 = new Vertex("map2", new ProcessorDescriptor(
        TokenProcessor.class.getName()),
        numMaps, MRHelpers.getMapResource(mapStageConf));
    mapVertex2.setJavaOpts(MRHelpers.getMapJavaOpts(mapStageConf));
    MRHelpers.updateEnvironmentForMRTasks(mapStageConf, mapEnv, true);
    mapVertex2.setTaskEnvironment(mapEnv);
    mapVertex2.addInput("MRInput", id, initializerClazz);

    Vertex mapVertex3 = new Vertex("map3", new ProcessorDescriptor(
        TokenProcessor.class.getName()),
        numMaps, MRHelpers.getMapResource(mapStageConf));
    mapVertex3.setJavaOpts(MRHelpers.getMapJavaOpts(mapStageConf));
    MRHelpers.updateEnvironmentForMRTasks(mapStageConf, mapEnv, true);
    mapVertex3.setTaskEnvironment(mapEnv);
    mapVertex3.addInput("MRInput", id, initializerClazz);
    
    byte[] finalReducePayload = MRHelpers.createUserPayloadFromConf(finalReduceConf);
    Vertex checkerVertex = new Vertex("checker",
        new ProcessorDescriptor(
            UnionProcessor.class.getName()).setUserPayload(finalReducePayload),
                1, MRHelpers.getReduceResource(finalReduceConf));
    checkerVertex.setJavaOpts(
        MRHelpers.getReduceJavaOpts(finalReduceConf));
    Map<String, String> reduceEnv = new HashMap<String, String>();
    MRHelpers.updateEnvironmentForMRTasks(finalReduceConf, reduceEnv, false);
    checkerVertex.setTaskEnvironment(reduceEnv);
    OutputDescriptor od = new OutputDescriptor(MROutput.class.getName())
      .setUserPayload(finalReducePayload);
    checkerVertex.addOutput("union", od, MROutputCommitter.class);

    Configuration partsConf = new Configuration(finalReduceConf);
    partsConf.set(FileOutputFormat.OUTDIR, outputPath+"-parts");
    byte[] partsPayload = MRHelpers.createUserPayloadFromConf(partsConf);
    
    DAG dag = new DAG("UnionExample");
    
    VertexGroup unionVertex = dag.createVertexGroup("union", mapVertex1, mapVertex2);
    OutputDescriptor od1 = new OutputDescriptor(MROutput.class.getName())
      .setUserPayload(partsPayload);
    Configuration allPartsConf = new Configuration(finalReduceConf);
    allPartsConf.set(FileOutputFormat.OUTDIR, outputPath+"-all-parts");
    byte[] allPartsPayload = MRHelpers.createUserPayloadFromConf(allPartsConf);
    OutputDescriptor od2 = new OutputDescriptor(MROutput.class.getName())
      .setUserPayload(allPartsPayload);
    unionVertex.addOutput("parts", od1, MROutputCommitter.class);
    checkerVertex.addOutput("all-parts", od2, MROutputCommitter.class);
    
    
    dag.addVertex(mapVertex1)
        .addVertex(mapVertex2)
        .addVertex(mapVertex3)
        .addVertex(checkerVertex)
        .addEdge(
            new Edge(mapVertex3, checkerVertex, new EdgeProperty(
                DataMovementType.SCATTER_GATHER, DataSourceType.PERSISTED,
                SchedulingType.SEQUENTIAL, 
                new OutputDescriptor(OnFileSortedOutput.class.getName())
                        .setUserPayload(mapPayload), 
                new InputDescriptor(ShuffledMergedInput.class.getName())
                        .setUserPayload(finalReducePayload))))
        .addEdge(
            new GroupInputEdge(unionVertex, checkerVertex, new EdgeProperty(
                DataMovementType.SCATTER_GATHER, DataSourceType.PERSISTED,
                SchedulingType.SEQUENTIAL,
                new OutputDescriptor(OnFileSortedOutput.class.getName())
                    .setUserPayload(mapPayload), 
                new InputDescriptor(ShuffledMergedInput.class.getName())
                    .setUserPayload(finalReducePayload)),
                new InputDescriptor(
                    ConcatenatedMergedKeyValuesInput.class.getName())));
    return dag;  
  }

  private static void waitForTezSessionReady(TezSession tezSession)
      throws IOException, TezException {
      while (true) {
        TezSessionStatus status = tezSession.getSessionStatus();
        if (status.equals(TezSessionStatus.SHUTDOWN)) {
          throw new RuntimeException("TezSession has already shutdown");
        }
        if (status.equals(TezSessionStatus.READY)) {
          return;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

  private static void printUsage() {
    System.err.println("Usage: " + " unionexample <in1> <out1>");
  }

  private Credentials credentials = new Credentials();
  
  public boolean run(String inputPath, String outputPath, Configuration conf) throws Exception {
    System.out.println("Running UnionExample");
    // conf and UGI
    TezConfiguration tezConf;
    if (conf != null) {
      tezConf = new TezConfiguration(conf);
    } else {
      tezConf = new TezConfiguration();
    }
    UserGroupInformation.setConfiguration(tezConf);
    String user = UserGroupInformation.getCurrentUser().getShortUserName();

    TezClient tezClient = new TezClient(tezConf);
    ApplicationId appId = tezClient.createApplication();
    
    // staging dir
    FileSystem fs = FileSystem.get(tezConf);
    String stagingDirStr = Path.SEPARATOR + "user" + Path.SEPARATOR
        + user + Path.SEPARATOR+ ".staging" + Path.SEPARATOR
        + Path.SEPARATOR + appId.toString();    
    Path stagingDir = new Path(stagingDirStr);
    tezConf.set(TezConfiguration.TEZ_AM_STAGING_DIR, stagingDirStr);
    stagingDir = fs.makeQualified(stagingDir);
    
    // security
    TokenCache.obtainTokensForNamenodes(credentials, new Path[] {stagingDir}, tezConf);
    TezClientUtils.ensureStagingDirExists(tezConf, stagingDir);

    tezConf.set(TezConfiguration.TEZ_AM_JAVA_OPTS,
        MRHelpers.getMRAMJavaOpts(tezConf));

    // No need to add jar containing this class as assumed to be part of
    // the tez jars.

    // TEZ-674 Obtain tokens based on the Input / Output paths. For now assuming staging dir
    // is the same filesystem as the one used for Input/Output.
    
    TezSession tezSession = null;
    AMConfiguration amConfig = new AMConfiguration(null,
        null, tezConf, credentials);
    
    TezSessionConfiguration sessionConfig =
        new TezSessionConfiguration(amConfig, tezConf);
    tezSession = new TezSession("UnionExampleSession", appId,
        sessionConfig);
    tezSession.start();

    DAGStatus dagStatus = null;
    DAGClient dagClient = null;
    String[] vNames = { "map1", "map2", "map3", "checker" };

    Set<StatusGetOpts> statusGetOpts = EnumSet.of(StatusGetOpts.GET_COUNTERS);
    try {
        if (fs.exists(new Path(outputPath))) {
          throw new FileAlreadyExistsException("Output directory "
              + outputPath + " already exists");
        }
        
        Map<String, LocalResource> localResources =
          new TreeMap<String, LocalResource>();
        
        DAG dag = createDAG(fs, tezConf, localResources,
            stagingDir, inputPath, outputPath);

        waitForTezSessionReady(tezSession);
        dagClient = tezSession.submitDAG(dag);
        //dagClient = tezClient.submitDAGApplication(dag, amConfig);

        // monitoring
        while (true) {
          dagStatus = dagClient.getDAGStatus(statusGetOpts);
          if(dagStatus.getState() == DAGStatus.State.RUNNING ||
              dagStatus.getState() == DAGStatus.State.SUCCEEDED ||
              dagStatus.getState() == DAGStatus.State.FAILED ||
              dagStatus.getState() == DAGStatus.State.KILLED ||
              dagStatus.getState() == DAGStatus.State.ERROR) {
            break;
          }
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            // continue;
          }
        }


        while (dagStatus.getState() == DAGStatus.State.RUNNING) {
          try {
            ExampleDriver.printDAGStatus(dagClient, vNames);
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              // continue;
            }
            dagStatus = dagClient.getDAGStatus(statusGetOpts);
          } catch (TezException e) {
            System.exit(-1);
          }
        }
        ExampleDriver.printDAGStatus(dagClient, vNames,
            true, true);
        System.out.println("DAG completed. " + "FinalState=" + dagStatus.getState());
        if (dagStatus.getState() != DAGStatus.State.SUCCEEDED) {
          System.out.println("DAG diagnostics: " + dagStatus.getDiagnostics());
          return false;
        }
        return true;
    } finally {
      fs.delete(stagingDir, true);
      tezSession.stop();
    }
  }

  public static void main(String[] args) throws Exception {
    if ((args.length%2) != 0) {
      printUsage();
      System.exit(2);
    }
    UnionExample job = new UnionExample();
    job.run(args[0], args[1], null);
  }
}
