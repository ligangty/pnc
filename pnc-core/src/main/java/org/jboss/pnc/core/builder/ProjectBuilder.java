package org.jboss.pnc.core.builder;

import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.jboss.pnc.core.BuildDriverFactory;
import org.jboss.pnc.core.RepositoryManagerFactory;
import org.jboss.pnc.core.exception.CoreException;
import org.jboss.pnc.model.BuildCollection;
import org.jboss.pnc.model.BuildStatus;
import org.jboss.pnc.model.ProjectBuildConfiguration;
import org.jboss.pnc.model.ProjectBuildResult;
import org.jboss.pnc.model.RepositoryType;
import org.jboss.pnc.spi.builddriver.BuildDriver;
import org.jboss.pnc.spi.datastore.Datastore;
import org.jboss.pnc.spi.environment.EnvironmentDriver;
import org.jboss.pnc.spi.environment.EnvironmentDriverProvider;
import org.jboss.pnc.spi.repositorymanager.RepositoryConfiguration;
import org.jboss.pnc.spi.repositorymanager.RepositoryManager;

import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.inject.Inject;

/**
 * Created by <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> on 2014-11-23.
 */
public class ProjectBuilder {

    @Inject
    BuildDriverFactory buildDriverFactory;

    @Inject
    RepositoryManagerFactory repositoryManagerFactory;

    @Inject
    Datastore datastore;

    @Inject
    EnvironmentDriverProvider environmentDriverProvider;

    @Inject
    private Logger log;

    public void buildProjects(Set<ProjectBuildConfiguration> projectsBuildConfigurations, BuildCollection collection ) throws CoreException,
            InterruptedException {

        TaskSet<ProjectBuildConfiguration> taskSet = new TaskSet<ProjectBuildConfiguration>();
        for (final ProjectBuildConfiguration projectBuildConfiguration : projectsBuildConfigurations) {
            taskSet.add(projectBuildConfiguration, projectBuildConfiguration.getDependencies());
        }

        Semaphore maxConcurrentTasks = new Semaphore(3); // TODO configurable

        while (true) {
            Task<ProjectBuildConfiguration> task = taskSet.getNext();
            if (task == null)
            {
                break;
            }
            log.info("Building task " + task);
            synchronized (taskSet) {
                maxConcurrentTasks.acquire();
            }

            Consumer<ProjectBuildResult> notifyTaskComplete = buildResult -> {
                if (buildResult.getStatus().equals(BuildStatus.SUCCESS)) {
                    task.completedSuccessfully();
                } else {
                    task.completedWithError();
                }
                log.finest("Notifying build completed " + task.getTask());
                synchronized (taskSet) {
                    maxConcurrentTasks.release();
                    taskSet.notify();
                }
            };

            task.setBuilding();
            buildProject(task.getTask(), collection, notifyTaskComplete);
        }
    }

    private void buildProject( ProjectBuildConfiguration projectBuildConfiguration,
                               BuildCollection buildCollection,
            Consumer<ProjectBuildResult> notifyTaskComplete) throws CoreException {
        BuildDriver buildDriver = buildDriverFactory.getBuildDriver(projectBuildConfiguration.getEnvironment().getBuildType());
        RepositoryManager repositoryManager = repositoryManagerFactory.getRepositoryManager(RepositoryType.MAVEN); // TODO
                                                                                                                          // configure
                                                                                                                          // per
                                                                                                                          // project

        RepositoryConfiguration repository =
            repositoryManager.createRepository( projectBuildConfiguration, buildCollection );

        buildDriver.setRepository( repository );

        EnvironmentDriver environmentDriver = environmentDriverProvider.getDriver(projectBuildConfiguration.getEnvironment()
                .getOperationalSystem());
        environmentDriver.buildEnvironment(projectBuildConfiguration.getEnvironment());

        buildDriver.startProjectBuild(projectBuildConfiguration,
                onBuildComplete(notifyTaskComplete, repository, repositoryManager));

    }

    Consumer<ProjectBuildResult> onBuildComplete(Consumer<ProjectBuildResult> notifyTaskComplete, RepositoryConfiguration repository, RepositoryManager repositoryManager) {
        return buildResult -> {
            storeResult(buildResult);
            // TODO if scratch etc
            repositoryManager.persistArtifacts( repository, buildResult );
            notifyTaskComplete.accept(buildResult);
        };
    }

    private void storeResult(ProjectBuildResult buildResult) {
        datastore.storeCompletedBuild(buildResult);
    }

}
