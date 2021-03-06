/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.managers;

import org.jboss.pnc.bpm.BpmEventType;
import org.jboss.pnc.bpm.BpmManager;
import org.jboss.pnc.bpm.task.MilestoneReleaseTask;
import org.jboss.pnc.mock.repository.ArtifactRepositoryMock;
import org.jboss.pnc.mock.repository.BuildRecordRepositoryMock;
import org.jboss.pnc.mock.repository.ProductMilestoneReleaseRepositoryMock;
import org.jboss.pnc.mock.repository.ProductMilestoneRepositoryMock;
import org.jboss.pnc.model.BuildRecord;
import org.jboss.pnc.model.ProductMilestone;
import org.jboss.pnc.model.ProductMilestoneRelease;
import org.jboss.pnc.rest.restmodel.causeway.BuildImportResultRest;
import org.jboss.pnc.rest.restmodel.causeway.BuildImportStatus;
import org.jboss.pnc.rest.restmodel.causeway.MilestoneReleaseResultRest;
import org.jboss.pnc.rest.restmodel.causeway.ReleaseStatus;
import org.jboss.pnc.spi.datastore.repositories.ProductMilestoneReleaseRepository;
import org.jboss.pnc.spi.datastore.repositories.ProductMilestoneRepository;
import org.jboss.pnc.spi.exception.CoreException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

/**
 * Author: Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 */
public class ProductMilestoneReleaseManagerTest {

    private static final String BREW_URL_PATTERN = "http://brew.example.com/build/%d/";

    @Mock
    private BpmManager bpmManager;

    private ProductMilestoneRepository milestoneRepository;
    private ProductMilestoneReleaseRepository releaseRepository;
    private BuildRecordRepositoryMock buildRecordRepository;

    private ProductMilestoneReleaseManager releaseManager;
    private final CallbackAnswer answer = new CallbackAnswer();

    private int milestoneIdSequence = 0;
    private int buildRecordIdSequence = 0;

    @Before
    public void setUp() throws CoreException {
        milestoneRepository = new ProductMilestoneRepositoryMock();
        releaseRepository = new ProductMilestoneReleaseRepositoryMock();
        buildRecordRepository = new BuildRecordRepositoryMock();

        MockitoAnnotations.initMocks(this);
        when(bpmManager.startTask(any())).then(answer);
        releaseManager = new ProductMilestoneReleaseManager(releaseRepository, bpmManager, new ArtifactRepositoryMock(), buildRecordRepository, milestoneRepository);
    }

    @Test
    public void shouldStoreSuccess() {
        // given
        ProductMilestone milestone = createMilestone();
        BuildRecord record = buildRecord(milestone);
        int brewBuildId = 100;
        // when
        release(milestone, brewBuildId, record);
        // then
        assertBrewAttributesInRecord(record, String.valueOf(brewBuildId), brewUrl(brewBuildId));
    }

    @Test
    public void shouldStoreSuccessForTwoBuilds() {
        // given
        ProductMilestone milestone = createMilestone();
        BuildRecord[] records = {buildRecord(milestone), buildRecord(milestone)};

        int brewBuildId = 1000;
        // when
        release(milestone, brewBuildId, records);

        // then
        for (int i = 0; i < 2; i++) {
            BuildRecord record = records[i];
            assertBrewAttributesInRecord(record, String.valueOf(brewBuildId + i), brewUrl(brewBuildId + i));
        }
    }

    private void release(ProductMilestone milestone, int brewBuildId, BuildRecord... records) {
        answer.callback = t -> t.notify(BpmEventType.BREW_IMPORT_SUCCESS, successfulReleaseResult(brewBuildId, records));
        releaseManager.startRelease(milestone, null);
        List<ProductMilestoneRelease> releases = releaseRepository.queryAll();
        assertThat(releases).hasSize(1);
    }

    private void assertBrewAttributesInRecord(BuildRecord record, String expectedBrewId, String expectedBrewLink) {
        assertThat(record.getAttributes()).isNotEmpty();

        assertThat(record.getAttribute(ProductMilestoneReleaseManager.BREW_ID)).isEqualTo(expectedBrewId);
        assertThat(record.getAttribute(ProductMilestoneReleaseManager.BREW_LINK)).isEqualTo(expectedBrewLink);
    }

    private MilestoneReleaseResultRest successfulReleaseResult(int brewBuildId, BuildRecord... records) {
        MilestoneReleaseResultRest result = new MilestoneReleaseResultRest();
        List<BuildImportResultRest> buildResults = new ArrayList<>();

        for (int i = 0; i < records.length; i++) {
            Integer recordId = records[i].getId();
            BuildImportResultRest buildResult =
                    new BuildImportResultRest(recordId, brewBuildId + i, brewUrl(brewBuildId + i), BuildImportStatus.SUCCESSFUL, null, null);
            buildResults.add(buildResult);
        }

        result.setBuilds(buildResults);
        result.setReleaseStatus(ReleaseStatus.SUCCESS);
        return result;
    }

    private String brewUrl(int brewBuildId) {
        return String.format(BREW_URL_PATTERN, brewBuildId);
    }

    private ProductMilestone createMilestone() {
        ProductMilestone milestone = new ProductMilestone();
        milestone.setId(milestoneIdSequence++);
        milestoneRepository.save(milestone);

        return milestone;
    }

    private BuildRecord buildRecord(ProductMilestone milestone) {
        BuildRecord record = new BuildRecord();
        record.setProductMilestone(milestone);
        record.setId(buildRecordIdSequence++);
        buildRecordRepository.save(record);
        return record;
    }

    private class CallbackAnswer implements Answer<Boolean> {
        private Consumer<MilestoneReleaseTask> callback;

        @Override
        public Boolean answer(InvocationOnMock invocation) throws Throwable {
            MilestoneReleaseTask task = invocation.getArgumentAt(0, MilestoneReleaseTask.class);
            callback.accept(task);
            return true;
        }


    }
}