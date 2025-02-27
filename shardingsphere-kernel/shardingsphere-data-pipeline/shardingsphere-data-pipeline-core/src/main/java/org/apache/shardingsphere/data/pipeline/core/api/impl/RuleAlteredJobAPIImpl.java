/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.core.api.impl;

import com.google.common.base.Preconditions;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.RuleAlteredJobAPI;
import org.apache.shardingsphere.data.pipeline.api.check.consistency.DataConsistencyCheckResult;
import org.apache.shardingsphere.data.pipeline.api.config.rulealtered.HandleConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.rulealtered.JobConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.rulealtered.WorkflowConfiguration;
import org.apache.shardingsphere.data.pipeline.api.job.JobStatus;
import org.apache.shardingsphere.data.pipeline.api.job.progress.JobProgress;
import org.apache.shardingsphere.data.pipeline.api.pojo.DataConsistencyCheckAlgorithmInfo;
import org.apache.shardingsphere.data.pipeline.api.pojo.JobInfo;
import org.apache.shardingsphere.data.pipeline.core.api.PipelineAPIFactory;
import org.apache.shardingsphere.data.pipeline.core.check.consistency.DataConsistencyChecker;
import org.apache.shardingsphere.data.pipeline.core.constant.DataPipelineConstants;
import org.apache.shardingsphere.data.pipeline.core.context.PipelineContext;
import org.apache.shardingsphere.data.pipeline.core.exception.PipelineDataConsistencyCheckFailedException;
import org.apache.shardingsphere.data.pipeline.core.exception.PipelineJobCreationException;
import org.apache.shardingsphere.data.pipeline.core.exception.PipelineJobExecutionException;
import org.apache.shardingsphere.data.pipeline.core.exception.PipelineJobNotFoundException;
import org.apache.shardingsphere.data.pipeline.scenario.rulealtered.RuleAlteredContext;
import org.apache.shardingsphere.data.pipeline.scenario.rulealtered.RuleAlteredJob;
import org.apache.shardingsphere.data.pipeline.scenario.rulealtered.RuleAlteredJobContext;
import org.apache.shardingsphere.data.pipeline.scenario.rulealtered.RuleAlteredJobSchedulerCenter;
import org.apache.shardingsphere.data.pipeline.scenario.rulealtered.RuleAlteredJobWorker;
import org.apache.shardingsphere.data.pipeline.spi.check.consistency.DataConsistencyCheckAlgorithm;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.domain.JobBriefInfo;
import org.apache.shardingsphere.infra.config.TypedSPIConfiguration;
import org.apache.shardingsphere.infra.config.algorithm.ShardingSphereAlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.algorithm.ShardingSphereAlgorithmFactory;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.eventbus.ShardingSphereEventBus;
import org.apache.shardingsphere.infra.yaml.config.pojo.YamlRootConfiguration;
import org.apache.shardingsphere.infra.yaml.engine.YamlEngine;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.rule.ScalingTaskFinishedEvent;
import org.apache.shardingsphere.scaling.core.job.check.EnvironmentCheckerFactory;
import org.apache.shardingsphere.scaling.core.job.environment.ScalingEnvironmentManager;
import org.apache.shardingsphere.spi.singleton.SingletonSPIRegistry;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public final class RuleAlteredJobAPIImpl extends AbstractPipelineJobAPIImpl implements RuleAlteredJobAPI {
    
    private static final Map<String, DataConsistencyCheckAlgorithm> DATA_CONSISTENCY_CHECK_ALGORITHM_MAP = new TreeMap<>(
            SingletonSPIRegistry.getTypedSingletonInstancesMap(DataConsistencyCheckAlgorithm.class));
    
    @Override
    public boolean isDefault() {
        return false;
    }
    
    @Override
    public List<JobInfo> list() {
        checkModeConfig();
        return getJobBriefInfos().map(each -> getJobInfo(each.getJobName())).collect(Collectors.toList());
    }
    
    private void checkModeConfig() {
        ModeConfiguration modeConfig = PipelineContext.getModeConfig();
        Preconditions.checkNotNull(modeConfig, "Mode configuration is required.");
        Preconditions.checkArgument("Cluster".equals(modeConfig.getType()), "Mode must be `Cluster`.");
    }
    
    private Stream<JobBriefInfo> getJobBriefInfos() {
        return PipelineAPIFactory.getJobStatisticsAPI().getAllJobsBriefInfo().stream().filter(each -> !each.getJobName().startsWith("_"));
    }
    
    private JobInfo getJobInfo(final String jobName) {
        JobInfo result = new JobInfo(jobName);
        JobConfigurationPOJO jobConfigPOJO = getElasticJobConfigPOJO(result.getJobId());
        JobConfiguration jobConfig = getJobConfig(jobConfigPOJO);
        result.setActive(!jobConfigPOJO.isDisabled());
        result.setShardingTotalCount(jobConfig.getHandleConfig().getJobShardingCount());
        result.setTables(jobConfig.getHandleConfig().getLogicTables());
        result.setCreateTime(jobConfigPOJO.getProps().getProperty("create_time"));
        result.setStopTime(jobConfigPOJO.getProps().getProperty("stop_time"));
        result.setJobParameter(jobConfigPOJO.getJobParameter());
        return result;
    }
    
    @Override
    public List<Long> getUncompletedJobIds(final String schemaName) {
        return getJobBriefInfos().filter(each -> {
            String jobId = each.getJobName();
            return isUncompletedJobOfSchema(schemaName, jobId);
        }).map(each -> Long.parseLong(each.getJobName())).collect(Collectors.toList());
    }
    
    private boolean isUncompletedJobOfSchema(final String schemaName, final String jobId) {
        JobConfigurationPOJO jobConfigPOJO;
        try {
            jobConfigPOJO = getElasticJobConfigPOJO(jobId);
        } catch (final PipelineJobNotFoundException ex) {
            log.warn("scaling job not found, jobId={}", jobId);
            return false;
        }
        JobConfiguration jobConfig = getJobConfig(jobConfigPOJO);
        HandleConfiguration handleConfig = jobConfig.getHandleConfig();
        WorkflowConfiguration workflowConfig;
        if (null == handleConfig || null == (workflowConfig = jobConfig.getWorkflowConfig())) {
            log.warn("handleConfig or workflowConfig null, jobId={}", jobId);
            return false;
        }
        if (!schemaName.equals(workflowConfig.getSchemaName())) {
            return false;
        }
        return !jobConfigPOJO.isDisabled();
    }
    
    @Override
    public Optional<String> start(final JobConfiguration jobConfig) {
        jobConfig.buildHandleConfig();
        if (jobConfig.getHandleConfig().getJobShardingCount() == 0) {
            log.warn("Invalid scaling job config!");
            throw new PipelineJobCreationException("handleConfig shardingTotalCount is 0");
        }
        log.info("Start scaling job by {}", jobConfig.getHandleConfig());
        PipelineAPIFactory.getGovernanceRepositoryAPI().persist(String.format("%s/%s",
                DataPipelineConstants.DATA_PIPELINE_ROOT, jobConfig.getHandleConfig().getJobId()), RuleAlteredJob.class.getName());
        PipelineAPIFactory.getGovernanceRepositoryAPI().persist(String.format("%s/%s/config",
                DataPipelineConstants.DATA_PIPELINE_ROOT, jobConfig.getHandleConfig().getJobId()), createJobConfig(jobConfig));
        return Optional.of(jobConfig.getHandleConfig().getJobId());
    }
    
    private String createJobConfig(final JobConfiguration jobConfig) {
        JobConfigurationPOJO jobConfigPOJO = new JobConfigurationPOJO();
        jobConfigPOJO.setJobName(jobConfig.getHandleConfig().getJobId());
        jobConfigPOJO.setShardingTotalCount(jobConfig.getHandleConfig().getJobShardingCount());
        jobConfigPOJO.setJobParameter(YamlEngine.marshal(jobConfig));
        jobConfigPOJO.getProps().setProperty("create_time", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        return YamlEngine.marshal(jobConfigPOJO);
    }
    
    @Override
    public Map<Integer, JobProgress> getProgress(final String jobId) {
        checkModeConfig();
        log.info("getProgress for job {}", jobId);
        JobConfiguration jobConfig = getJobConfig(jobId);
        return getProgress(jobConfig);
    }
    
    @Override
    public Map<Integer, JobProgress> getProgress(final JobConfiguration jobConfig) {
        String jobId = jobConfig.getHandleConfig().getJobId();
        JobConfigurationPOJO jobConfigPOJO = getElasticJobConfigPOJO(jobId);
        return IntStream.range(0, jobConfig.getHandleConfig().getJobShardingCount()).boxed().collect(LinkedHashMap::new, (map, each) -> {
            JobProgress jobProgress = PipelineAPIFactory.getGovernanceRepositoryAPI().getJobProgress(jobId, each);
            if (null != jobProgress) {
                jobProgress.setActive(!jobConfigPOJO.isDisabled());
            }
            map.put(each, jobProgress);
        }, LinkedHashMap::putAll);
    }
    
    @Override
    public void stopClusterWriteDB(final String jobId) {
        checkModeConfig();
        //TODO stopClusterWriteDB
    }
    
    @Override
    public void restoreClusterWriteDB(final String jobId) {
        checkModeConfig();
        log.info("restoreClusterWriteDB for job {}", jobId);
        JobConfiguration jobConfig = getJobConfig(jobId);
        restoreClusterWriteDB(jobConfig);
    }
    
    @Override
    public void restoreClusterWriteDB(final JobConfiguration jobConfig) {
        // TODO restoreClusterWriteDB
    }
    
    @Override
    public Collection<DataConsistencyCheckAlgorithmInfo> listDataConsistencyCheckAlgorithms() {
        checkModeConfig();
        return DATA_CONSISTENCY_CHECK_ALGORITHM_MAP.values()
                .stream().map(each -> {
                    DataConsistencyCheckAlgorithmInfo algorithmInfo = new DataConsistencyCheckAlgorithmInfo();
                    algorithmInfo.setType(each.getType());
                    algorithmInfo.setDescription(each.getDescription());
                    algorithmInfo.setSupportedDatabaseTypes(each.getSupportedDatabaseTypes());
                    algorithmInfo.setProvider(each.getProvider());
                    return algorithmInfo;
                }).collect(Collectors.toList());
    }
    
    @Override
    public boolean isDataConsistencyCheckNeeded(final String jobId) {
        log.info("isDataConsistencyCheckNeeded for job {}", jobId);
        JobConfiguration jobConfig = getJobConfig(jobId);
        return isDataConsistencyCheckNeeded(jobConfig);
    }
    
    @Override
    public boolean isDataConsistencyCheckNeeded(final JobConfiguration jobConfig) {
        RuleAlteredContext ruleAlteredContext = RuleAlteredJobWorker.createRuleAlteredContext(jobConfig);
        return isDataConsistencyCheckNeeded(ruleAlteredContext);
    }
    
    private boolean isDataConsistencyCheckNeeded(final RuleAlteredContext ruleAlteredContext) {
        return null != ruleAlteredContext.getDataConsistencyCheckAlgorithm();
    }
    
    @Override
    public Map<String, DataConsistencyCheckResult> dataConsistencyCheck(final String jobId) {
        checkModeConfig();
        log.info("Data consistency check for job {}", jobId);
        JobConfiguration jobConfig = getJobConfig(jobId);
        return dataConsistencyCheck(jobConfig);
    }
    
    @Override
    public Map<String, DataConsistencyCheckResult> dataConsistencyCheck(final JobConfiguration jobConfig) {
        RuleAlteredContext ruleAlteredContext = RuleAlteredJobWorker.createRuleAlteredContext(jobConfig);
        if (!isDataConsistencyCheckNeeded(ruleAlteredContext)) {
            log.info("dataConsistencyCheckAlgorithm is not configured, data consistency check is ignored.");
            return Collections.emptyMap();
        }
        return dataConsistencyCheck0(jobConfig, ruleAlteredContext.getDataConsistencyCheckAlgorithm());
    }
    
    @Override
    public Map<String, DataConsistencyCheckResult> dataConsistencyCheck(final String jobId, final String algorithmType) {
        checkModeConfig();
        log.info("Data consistency check for job {}, algorithmType: {}", jobId, algorithmType);
        JobConfiguration jobConfig = getJobConfig(jobId);
        TypedSPIConfiguration typedSPIConfig = new ShardingSphereAlgorithmConfiguration(algorithmType, new Properties());
        DataConsistencyCheckAlgorithm checkAlgorithm = ShardingSphereAlgorithmFactory.createAlgorithm(typedSPIConfig, DataConsistencyCheckAlgorithm.class);
        return dataConsistencyCheck0(jobConfig, checkAlgorithm);
    }
    
    private Map<String, DataConsistencyCheckResult> dataConsistencyCheck0(final JobConfiguration jobConfig, final DataConsistencyCheckAlgorithm checkAlgorithm) {
        String jobId = jobConfig.getHandleConfig().getJobId();
        DataConsistencyChecker dataConsistencyChecker = EnvironmentCheckerFactory.newInstance(jobConfig);
        Map<String, DataConsistencyCheckResult> result = dataConsistencyChecker.checkRecordsCount();
        if (result.values().stream().allMatch(DataConsistencyCheckResult::isRecordsCountMatched)) {
            Map<String, Boolean> contentCheckResult = dataConsistencyChecker.checkRecordsContent(checkAlgorithm);
            result.forEach((key, value) -> value.setRecordsContentMatched(contentCheckResult.getOrDefault(key, false)));
        }
        log.info("Scaling job {} with check algorithm '{}' data consistency checker result {}", jobId, checkAlgorithm.getClass().getName(), result);
        PipelineAPIFactory.getGovernanceRepositoryAPI().persistJobCheckResult(jobId, aggregateDataConsistencyCheckResults(jobId, result));
        return result;
    }
    
    @Override
    public boolean aggregateDataConsistencyCheckResults(final String jobId, final Map<String, DataConsistencyCheckResult> checkResultMap) {
        if (checkResultMap.isEmpty()) {
            return false;
        }
        for (Entry<String, DataConsistencyCheckResult> entry : checkResultMap.entrySet()) {
            boolean recordsCountMatched = entry.getValue().isRecordsCountMatched();
            boolean recordsContentMatched = entry.getValue().isRecordsContentMatched();
            if (!recordsContentMatched || !recordsCountMatched) {
                log.error("Scaling job: {}, table: {} data consistency check failed, recordsContentMatched: {}, recordsCountMatched: {}",
                    jobId, entry.getKey(), recordsContentMatched, recordsCountMatched);
                return false;
            }
        }
        return true;
    }
    
    @Override
    public void switchClusterConfiguration(final String jobId) {
        checkModeConfig();
        log.info("Switch cluster configuration for job {}", jobId);
        JobConfiguration jobConfig = getJobConfig(jobId);
        switchClusterConfiguration(jobConfig);
    }
    
    @Override
    public void switchClusterConfiguration(final JobConfiguration jobConfig) {
        String jobId = jobConfig.getHandleConfig().getJobId();
        RuleAlteredContext ruleAlteredContext = RuleAlteredJobWorker.createRuleAlteredContext(jobConfig);
        if (isDataConsistencyCheckNeeded(ruleAlteredContext)) {
            Optional<Boolean> checkResultOptional = PipelineAPIFactory.getGovernanceRepositoryAPI().getJobCheckResult(jobId);
            if (!checkResultOptional.isPresent() || !checkResultOptional.get()) {
                throw new PipelineDataConsistencyCheckFailedException("Data consistency check not finished or failed.");
            }
        }
        Optional<Collection<RuleAlteredJobContext>> optionalJobContexts = RuleAlteredJobSchedulerCenter.getJobContexts(jobId);
        optionalJobContexts.ifPresent(jobContexts -> jobContexts.forEach(each -> each.setStatus(JobStatus.ALMOST_FINISHED)));
        YamlRootConfiguration yamlRootConfig = YamlEngine.unmarshal(jobConfig.getPipelineConfig().getTarget().getParameter(), YamlRootConfiguration.class, true);
        WorkflowConfiguration workflowConfig = jobConfig.getWorkflowConfig();
        String schemaName = workflowConfig.getSchemaName();
        String ruleCacheId = workflowConfig.getRuleCacheId();
        ScalingTaskFinishedEvent taskFinishedEvent = new ScalingTaskFinishedEvent(schemaName, yamlRootConfig, ruleCacheId);
        ShardingSphereEventBus.getInstance().post(taskFinishedEvent);
        optionalJobContexts.ifPresent(jobContexts -> jobContexts.forEach(each -> {
            each.setStatus(JobStatus.FINISHED);
            RuleAlteredJobSchedulerCenter.persistJobProgress(each);
        }));
        stop(jobId);
    }
    
    @Override
    public void reset(final String jobId) {
        checkModeConfig();
        log.info("Scaling job {} reset target table", jobId);
        PipelineAPIFactory.getGovernanceRepositoryAPI().deleteJobProgress(jobId);
        try {
            new ScalingEnvironmentManager().cleanupTargetTables(getJobConfig(jobId));
        } catch (final SQLException ex) {
            throw new PipelineJobExecutionException("Reset target table failed for job " + jobId);
        }
    }
    
    @Override
    public JobConfiguration getJobConfig(final String jobId) {
        return getJobConfig(getElasticJobConfigPOJO(jobId));
    }
    
    private JobConfiguration getJobConfig(final JobConfigurationPOJO elasticJobConfigPOJO) {
        return YamlEngine.unmarshal(elasticJobConfigPOJO.getJobParameter(), JobConfiguration.class, true);
    }
}
